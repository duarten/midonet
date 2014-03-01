/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.netlink;

/** Netlink protocols. */
public enum NetlinkProtocol {

    NETLINK_ROUTE(0),
    NETLINK_W1(1),
    NETLINK_USERSOCK(2),
    NETLINK_FIREWALL(3),
    NETLINK_INET_DIAG(4),
    NETLINK_NFLOG(5),
    NETLINK_XFRM(6),
    NETLINK_SELINUX(7),
    NETLINK_ISCSI(8),
    NETLINK_AUDIT(9),
    NETLINK_FIB_LOOKUP(10),
    NETLINK_CONNECTOR(11),
    NETLINK_NETFILTER(12),
    NETLINK_IP6_FW(13),
    NETLINK_DNRTMSG(14),
    NETLINK_KOBJECT_UEVENT(15),
    NETLINK_GENERIC(16),
    NETLINK_SCSITRANSPORT(18),
    NETLINK_ECRYPTFS(19),
    NETLINK_RDMA(20),
    NETLINK_CRYPTO(21);

    private final int value;

    private NetlinkProtocol(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }
}