//go:build windows

package wintunmgr

import (
	"fmt"
	"net"
	"os/exec"
	"strings"
	"unsafe"

	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/registry"
)

// ifName is the Wintun adapter alias. Fixed so the adapter (and its config)
// is reused across sessions.
const ifName = "SwiftSSTP"

// undoOp rolls back one configuration step.
type undoOp struct {
	desc string
	fn   func() error
}

func run(name string, args ...string) (string, error) {
	out, err := exec.Command(name, args...).CombinedOutput()
	if err != nil {
		return "", fmt.Errorf("%s %s: %v (%s)", name, strings.Join(args, " "),
			err, strings.TrimSpace(string(out)))
	}
	return string(out), nil
}

func runPS(script string) error {
	_, err := run("powershell", "-NoProfile", "-NonInteractive", "-Command", script)
	return err
}

// defaultGateway4 reads the current IPv4 default gateway BEFORE any routes
// change; the SSTP server host route is installed via it so the control
// connection survives the default-route flip.
func defaultGateway4() net.IP {
	out, err := run("route", "print", "-4")
	if err != nil {
		return nil
	}
	for _, line := range strings.Split(out, "\n") {
		f := strings.Fields(line)
		if len(f) >= 3 && f[0] == "0.0.0.0" && f[1] == "0.0.0.0" {
			if gw := net.ParseIP(f[2]); gw != nil && gw.To4() != nil {
				return gw
			}
		}
	}
	return nil
}

// resolveServer resolves the SSTP endpoint while the original routing table
// is still intact (system DNS).
func resolveServer(host string) net.IP {
	if ip := net.ParseIP(host); ip != nil {
		return ip
	}
	ips, err := net.LookupIP(host)
	if err != nil || len(ips) == 0 {
		return nil
	}
	for _, ip := range ips {
		if ip.To4() != nil {
			return ip
		}
	}
	return ips[0]
}

// addHostRoute pins the server address to the original default gateway.
// Called BEFORE any default route is added (the task's ordering rule).
func addHostRoute(dst, gw net.IP) (undoOp, error) {
	if dst == nil || gw == nil || dst.To4() == nil || gw.To4() == nil {
		return undoOp{desc: "host route (skipped)", fn: func() error { return nil }},
			fmt.Errorf("host route skipped: dst=%v gw=%v", dst, gw)
	}
	_, _ = run("route", "delete", dst.String()) // drop any stale entry first
	if _, err := run("route", "add", dst.String(), "mask", "255.255.255.255",
		gw.String(), "metric", "1"); err != nil {
		return undoOp{}, err
	}
	return undoOp{
		desc: fmt.Sprintf("host route %s via %s", dst, gw),
		fn: func() error {
			_, e := run("route", "delete", dst.String())
			return e
		},
	}, nil
}

// configureAddress4 sets the inner address (point-to-point /32) and MTU.
func configureAddress4(local net.IP, mtu int) ([]undoOp, error) {
	var ops []undoOp
	mask := "255.255.255.255"
	if _, err := run("netsh", "interface", "ipv4", "set", "address",
		"name="+ifName, "source=static", "address="+local.String(),
		"mask="+mask); err != nil {
		return nil, err
	}
	ops = append(ops, undoOp{
		desc: "address " + local.String(),
		fn: func() error {
			_, e := run("netsh", "interface", "ipv4", "delete", "address",
				"name="+ifName, "address="+local.String())
			return e
		},
	})
	if mtu > 0 {
		if _, err := run("netsh", "interface", "ipv4", "set", "subinterface",
			"name="+ifName, fmt.Sprintf("mtu=%d", mtu), "store=persistent"); err != nil {
			return ops, fmt.Errorf("mtu: %w", err)
		}
	}
	return ops, nil
}

// setInterfaceMetric1 disables auto-metric so our default route (metric 1)
// beats the original default (wireguard-windows does the same: automatic
// metric off, metric 0).
func setInterfaceMetric1() {
	if _, err := run("netsh", "interface", "ipv4", "set", "interface",
		"interface="+ifName, "metric=1"); err != nil {
		_ = runPS(fmt.Sprintf("Set-NetIPInterface -InterfaceAlias '%s' -AddressFamily IPv4 -InterfaceMetric 1", ifName))
	}
}

// addDefaultRoute4 installs 0.0.0.0/0 on-link (NextHop 0.0.0.0, exactly
// what wireguard-windows' addressconfig.go does) with fallbacks.
func addDefaultRoute4() error {
	attempts := [][]string{
		{"interface", "ipv4", "add", "route", "prefix=0.0.0.0/0",
			"interface=" + ifName, "nexthop=0.0.0.0", "metric=1", "store=active"},
		{"interface", "ipv4", "add", "route", "prefix=0.0.0.0/0",
			"interface=" + ifName, "metric=1", "store=active"},
	}
	var errs []string
	for _, args := range attempts {
		if _, err := run("netsh", args...); err == nil {
			return nil
		} else {
			errs = append(errs, err.Error())
		}
	}
	if err := runPS(fmt.Sprintf(
		"New-NetRoute -DestinationPrefix '0.0.0.0/0' -InterfaceAlias '%s' -NextHop '0.0.0.0' -RouteMetric 1 -PolicyStore ActiveStore | Out-Null",
		ifName)); err == nil {
		return nil
	} else {
		errs = append(errs, err.Error())
	}
	return fmt.Errorf("add default route: %s", strings.Join(errs, "; "))
}

func deleteDefaultRoute4() error {
	if _, err := run("netsh", "interface", "ipv4", "delete", "route",
		"prefix=0.0.0.0/0", "interface="+ifName); err == nil {
		return nil
	}
	return runPS(fmt.Sprintf(
		"Remove-NetRoute -DestinationPrefix '0.0.0.0/0' -InterfaceAlias '%s' -Confirm:$false -ErrorAction SilentlyContinue",
		ifName))
}

// setDNS4 installs the tunnel resolvers on our adapter: netsh first, then
// PowerShell, then the registry (Tcpip\Parameters\Interfaces\<guid>).
func setDNS4(servers []net.IP) (undoOp, error) {
	if len(servers) == 0 {
		return undoOp{desc: "dns (none)", fn: func() error { return nil }}, nil
	}
	if err := setDNS4Netsh(servers); err == nil {
		// fall through to undo definition
	} else if err := setDNS4PowerShell(servers); err == nil {
		// fall through
	} else if err := setDNS4Registry(servers); err != nil {
		return undoOp{}, fmt.Errorf("set dns (netsh/powershell/registry all failed): %w", err)
	}
	joined := joinServers(servers)
	return undoOp{
		desc: "dns " + joined,
		fn: func() error {
			if _, err := run("netsh", "interface", "ipv4", "set", "dnsservers",
				"name="+ifName, "source=dhcp"); err == nil {
				return nil
			}
			if err := runPS(fmt.Sprintf(
				"Set-DnsClientServerAddress -InterfaceAlias '%s' -ResetServerAddresses -ErrorAction SilentlyContinue",
				ifName)); err == nil {
				return nil
			}
			return clearDNS4Registry()
		},
	}, nil
}

func setDNS4Netsh(servers []net.IP) error {
	if _, err := run("netsh", "interface", "ipv4", "set", "dnsservers",
		"name="+ifName, "source=static", "address="+servers[0].String(),
		"register=primary", "validate=no"); err != nil {
		return err
	}
	for i, s := range servers[1:] {
		if _, err := run("netsh", "interface", "ipv4", "add", "dnsservers",
			"name="+ifName, "address="+s.String(),
			fmt.Sprintf("index=%d", i+2), "validate=no"); err != nil {
			return err
		}
	}
	return nil
}

func setDNS4PowerShell(servers []net.IP) error {
	quoted := make([]string, 0, len(servers))
	for _, s := range servers {
		quoted = append(quoted, "'"+s.String()+"'")
	}
	return runPS(fmt.Sprintf("Set-DnsClientServerAddress -InterfaceAlias '%s' -ServerAddresses @(%s)",
		ifName, strings.Join(quoted, ",")))
}

func setDNS4Registry(servers []net.IP) error {
	key, err := openInterfacesKey(registry.LOCAL_MACHINE,
		`SYSTEM\CurrentControlSet\Services\Tcpip\Parameters\Interfaces`, ifName)
	if err != nil {
		return err
	}
	defer key.Close()
	return key.SetStringValue("NameServer", joinServers(servers))
}

func clearDNS4Registry() error {
	key, err := openInterfacesKey(registry.LOCAL_MACHINE,
		`SYSTEM\CurrentControlSet\Services\Tcpip\Parameters\Interfaces`, ifName)
	if err != nil {
		return err
	}
	defer key.Close()
	if err := key.DeleteValue("NameServer"); err != nil && err != registry.ErrNotExist {
		return err
	}
	return nil
}

// openInterfacesKey finds HKLM\...\Interfaces\{guid} by adapter friendly name.
func openInterfacesKey(root registry.Key, base, friendly string) (registry.Key, error) {
	guid, err := adapterGUID(friendly)
	if err != nil {
		return 0, err
	}
	return registry.OpenKey(root, base+`\`+guid, registry.SET_VALUE|registry.QUERY_VALUE)
}

// adapterGUID maps an interface friendly name ("SwiftSSTP") to its
// "{GUID}" registry key name via GetAdaptersAddresses.
func adapterGUID(friendly string) (string, error) {
	size := uint32(2048)
	for try := 0; try < 3; try++ {
		buf := make([]byte, size)
		first := (*windows.IpAdapterAddresses)(unsafe.Pointer(&buf[0]))
		err := windows.GetAdaptersAddresses(windows.AF_INET, 0, 0, first, &size)
		if err == windows.ERROR_BUFFER_OVERFLOW {
			continue
		}
		if err != nil {
			return "", fmt.Errorf("GetAdaptersAddresses: %w", err)
		}
		for a := first; a != nil; a = a.Next {
			if a.FriendlyName == nil || a.AdapterName == nil {
				continue
			}
			if !strings.EqualFold(windows.UTF16PtrToString(a.FriendlyName), friendly) {
				continue
			}
			return windows.BytePtrToString(a.AdapterName), nil
		}
		return "", fmt.Errorf("adapter %q not found", friendly)
	}
	return "", fmt.Errorf("adapter %q: GetAdaptersAddresses buffer never fit", friendly)
}

func joinServers(servers []net.IP) string {
	parts := make([]string, 0, len(servers))
	for _, s := range servers {
		parts = append(parts, s.String())
	}
	return strings.Join(parts, ",")
}
