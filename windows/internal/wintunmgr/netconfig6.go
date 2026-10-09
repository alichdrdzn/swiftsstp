//go:build windows

package wintunmgr

import (
	"fmt"
	"net"

	"golang.org/x/sys/windows/registry"
)

// configureAddress6 / addDefaultRoute6 / deleteDefaultRoute6 / setDNS6 are
// the IPv6 mirrors, used when the server negotiated an IPv6 inner address
// (Result.LocalIP is v6).

func configureAddress6(local net.IP, mtu int) ([]undoOp, error) {
	var ops []undoOp
	if _, err := run("netsh", "interface", "ipv6", "add", "address",
		"interface="+ifName, "address="+local.String(), "store=persistent"); err != nil {
		// Already-present address is fine.
		if _, err2 := run("netsh", "interface", "ipv6", "show", "address",
			"interface="+ifName); err2 != nil {
			return nil, err
		}
	}
	ops = append(ops, undoOp{
		desc: "address6 " + local.String(),
		fn: func() error {
			_, e := run("netsh", "interface", "ipv6", "delete", "address",
				"interface="+ifName, "address="+local.String())
			return e
		},
	})
	if mtu > 0 {
		if _, err := run("netsh", "interface", "ipv6", "set", "subinterface",
			"name="+ifName, fmt.Sprintf("mtu=%d", mtu), "store=persistent"); err != nil {
			return ops, fmt.Errorf("mtu6: %w", err)
		}
	}
	return ops, nil
}

func addDefaultRoute6() error {
	if _, err := run("netsh", "interface", "ipv6", "add", "route",
		"prefix=::/0", "interface="+ifName, "metric=1", "store=active"); err == nil {
		return nil
	}
	return runPS(fmt.Sprintf(
		"New-NetRoute -DestinationPrefix '::/0' -InterfaceAlias '%s' -NextHop '::' -RouteMetric 1 -PolicyStore ActiveStore | Out-Null",
		ifName))
}

func deleteDefaultRoute6() error {
	if _, err := run("netsh", "interface", "ipv6", "delete", "route",
		"prefix=::/0", "interface="+ifName); err == nil {
		return nil
	}
	return runPS(fmt.Sprintf(
		"Remove-NetRoute -DestinationPrefix '::/0' -InterfaceAlias '%s' -Confirm:$false -ErrorAction SilentlyContinue",
		ifName))
}

func setDNS6(servers []net.IP) (undoOp, error) {
	if len(servers) == 0 {
		return undoOp{desc: "dns6 (none)", fn: func() error { return nil }}, nil
	}
	if _, err := run("netsh", "interface", "ipv6", "set", "dnsservers",
		"name="+ifName, "source=static", "address="+servers[0].String(),
		"register=primary", "validate=no"); err == nil {
		for i, s := range servers[1:] {
			_, _ = run("netsh", "interface", "ipv6", "add", "dnsservers",
				"name="+ifName, "address="+s.String(),
				fmt.Sprintf("index=%d", i+2), "validate=no")
		}
	} else {
		// Registry fallback under Tcpip6.
		key, kerr := openInterfacesKey(registry.LOCAL_MACHINE,
			`SYSTEM\CurrentControlSet\Services\Tcpip6\Parameters\Interfaces`, ifName)
		if kerr != nil {
			return undoOp{}, fmt.Errorf("dns6 netsh and registry: %v / %w", err, kerr)
		}
		if err := key.SetStringValue("NameServer", joinServers(servers)); err != nil {
			key.Close()
			return undoOp{}, err
		}
		key.Close()
	}
	return undoOp{
		desc: "dns6 " + joinServers(servers),
		fn: func() error {
			if _, err := run("netsh", "interface", "ipv6", "set", "dnsservers",
				"name="+ifName, "source=dhcp"); err == nil {
				return nil
			}
			key, kerr := openInterfacesKey(registry.LOCAL_MACHINE,
				`SYSTEM\CurrentControlSet\Services\Tcpip6\Parameters\Interfaces`, ifName)
			if kerr != nil {
				return kerr
			}
			defer key.Close()
			if err := key.DeleteValue("NameServer"); err != nil && err != registry.ErrNotExist {
				return err
			}
			return nil
		},
	}, nil
}
