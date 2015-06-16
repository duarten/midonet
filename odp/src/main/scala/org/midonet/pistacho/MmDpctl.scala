/*
 * Copyright 2015 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.pistacho

import java.nio.ByteBuffer
import java.util.Arrays
import java.util.concurrent.{TimeUnit, TimeoutException}

import scala.collection.JavaConversions._
import scala.concurrent.duration._
import scala.util.Try
import scala.util.control.NonFatal

import org.rogach.scallop._
import rx.functions.Func1
import rx.subjects.ReplaySubject
import org.slf4j.LoggerFactory

import org.midonet.netlink._
import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.netlink.exceptions.NetlinkException.ErrorCode
import org.midonet.odp._
import org.midonet.odp.flows._
import org.midonet.odp.ports.NetDevPort
import org.midonet.packets.{IPv4Addr, MAC}
import org.midonet.util.concurrent.NanoClock
import org.midonet.util.functors._

class DpCtx(channel: NetlinkChannel, bufSize: Int, timeout: Duration) {
    val log = LoggerFactory.getLogger("mm-dpctl")

    val families = OvsNetlinkFamilies.discover(channel)
    val proto = new OvsProtocol(channel.getLocalAddress.getPid, families)
    val writer = new NetlinkBlockingWriter(channel)
    val reader = new NetlinkTimeoutReader( channel, timeout)
    val buf = BytesUtil.instance.allocateDirect(bufSize)

    val broker = new NetlinkRequestBroker(
        writer, reader, 1, buf.capacity(), buf, NanoClock.DEFAULT, timeout)

    private def writeRead[T](f: ByteBuffer => T): T = {
        writer.write(buf)
        buf.clear()
        reader.read(buf)
        buf.position(NetlinkMessage.GENL_HEADER_SIZE)
        val deserialized = f(buf)
        buf.clear()
        deserialized
    }

    private def enumRequest[T](
        f: ByteBuffer => T)
        (prepare: ByteBuffer => Unit): Iterable[T] = {
        val seq = broker.nextSequence()
        val subject = ReplaySubject.create[ByteBuffer]()
        prepare(broker.get(seq))
        broker.publishRequest(seq, subject)
        broker.writePublishedRequests()
        broker.readReply()
        subject.map[T](makeFunc1(f)).toBlocking.toIterable
    }

    private object isMissing {
        def unapply(ex: NetlinkException) =
            ex.getErrorCodeEnum match {
                case ErrorCode.ENODEV | ErrorCode.ENOENT | ErrorCode.ENXIO =>
                    Some(ex)
                case _ =>
                    None
            }
    }

    def getDatapath(name: String): Datapath = {
        proto.prepareDatapathGet(0, name, buf)
        val dp = writeRead(Datapath.deserializer.deserializeFrom)
        log.info(s"Got datapath $dp")
        dp
    }

    def createDatapath(name: String): Datapath = {
        proto.prepareDatapathCreate(name, buf)
        val dp = writeRead(Datapath.deserializer.deserializeFrom)
        log.info(s"Got datapath $dp")
        dp
    }

    def deleteDatapath(name: String): Unit =
        try {
            proto.prepareDatapathDel(0, name, buf)
            writeRead(Datapath.deserializer.deserializeFrom)
        } catch { case isMissing(_) => }

    def listDatapaths(): Iterable[Datapath] =
        enumRequest(Datapath.deserializer.deserializeFrom) {
            proto.prepareDatapathEnumerate
        }

    def getOrCreateDpPort(dp: Datapath, portName: String): DpPort = {
        val port =
            try {
                getDpPort(dp, portName)
            } catch { case isMissing(_) =>
                buf.clear()
                createDpPort(dp, portName)
            }
        log.info(s"Got DP port $portName at index ${port.getPortNo}")
        port
    }

    def getDpPort(dp: Datapath, portName: String): DpPort = {
        proto.prepareDpPortGet(dp.getIndex, null, portName, buf)
        writeRead[DpPort](DpPort.deserializer.deserializeFrom)
    }

    def createDpPort(dp: Datapath, portName: String): DpPort = {
        log.info(s"Creating DP port $portName")
        val port = new NetDevPort(portName)
        proto.prepareDpPortCreate(dp.getIndex, port, buf)
        writeRead[DpPort](DpPort.deserializer.deserializeFrom)
    }

    def deleteDpPort(dp: Datapath, portName: String): Unit =
        try {
            val port = getDpPort(dp, portName)
            proto.prepareDpPortDelete(dp.getIndex, port, buf)
            log.info(s"Deleting DP port $portName")
            writeRead(identity)
        } catch { case isMissing(_) => }

    def listDpPorts(dp: Datapath): Iterable[DpPort] =
        enumRequest(DpPort.deserializer.deserializeFrom) {
            proto.prepareDpPortEnum(dp.getIndex, _)
        }

    def createFlow(
            dp: Datapath,
            fmatch: FlowMatch,
            actions: java.util.List[FlowAction],
            mask: FlowMask = null): Unit = {
        proto.prepareFlowCreate(
            dp.getIndex, fmatch.getKeys, actions, mask, buf, NLFlag.ACK)
        log.info(s"Creating flow for $fmatch with ${if (mask ne null) mask else "no mask"}")
        writeRead(identity)
        buf.clear()
    }

    def deleteFlow(dp: Datapath, fmatch: FlowMatch): Unit = {
        log.info(s"Deleting flow $fmatch")
        try {
            proto.prepareFlowDelete(dp.getIndex, fmatch.getKeys, buf)
        } catch { case isMissing(_) => }
        buf.clear()
    }

    def dumpDatapath(dp: Datapath): Iterable[Flow] =
        enumRequest(Flow.deserializer.deserializeFrom) {
            proto.prepareFlowEnum(dp.getIndex, _)
        }
}

trait DpCommand {
    def run(ctx: DpCtx): Unit
}

object FlowsCtl extends Subcommand("flows") with DpCommand {
    private val NO_MAC = MAC.fromAddress(Array[Byte](0, 0, 0, 0, 0, 0))
    private val NO_IP = IPv4Addr.fromInt(0)

    implicit val parseIp = new ValueConverter[IPv4Addr] {
        def parse(s: List[(String, List[String])]): Either[String,Option[IPv4Addr]] =
            s match {
                case (_, ip :: Nil) :: Nil => Right(Try(IPv4Addr(ip)).toOption)
                case Nil => Right(None)
                case _ => Left("IP incorrectly specified")
            }

        val tag = scala.reflect.runtime.universe.typeTag[IPv4Addr]
        val argType = org.rogach.scallop.ArgType.SINGLE
    }

    implicit val parseProto = new ValueConverter[IpProtocol] {
        def parse(s: List[(String, List[String])]): Either[String,Option[IpProtocol]] =
            s match {
                case (_, proto :: Nil) :: Nil =>
                    Right(Try(IpProtocol.valueOf(proto.toUpperCase)).toOption)
                case Nil => Right(None)
                case _ => Left("IP protocol incorrectly specified")
            }

        val tag = scala.reflect.runtime.universe.typeTag[IpProtocol]
        val argType = org.rogach.scallop.ArgType.SINGLE
    }

    implicit val parseMac = new ValueConverter[MAC] {
        def parse(s: List[(String, List[String])]): Either[String,Option[MAC]] =
            s match {
                case (_, mac :: Nil) :: Nil =>
                    Right(Try(MAC.fromString(mac)).toOption)
                case Nil => Right(None)
                case _ => Left("IP protocol incorrectly specified")
            }

        val tag = scala.reflect.runtime.universe.typeTag[MAC]
        val argType = org.rogach.scallop.ArgType.SINGLE
    }

    descr("flow operations (create, delete)")

    val opts = new ScallopConf(args) {
        val dp = opt[String](
            "datapath",
            descr = "the datapath where to create/delete a flow",
            required = true)
        val inPort = opt[String](
            "input",
            short = 'i',
            descr = "input interface",
            required = true)
        val outPort = opt[String](
            "output",
            short = 'o',
            descr = "output interface",
            required = true)
        val srcMac = opt[MAC](
            "src-mac",
            noshort = true,
            descr = "source MAC address")
        val dstMac = opt[MAC](
            "dst-mac",
            noshort = true,
            descr = "destination MAC address")
        val srcIp = opt[IPv4Addr](
            "src-ip",
            noshort = true,
            descr = "source IP/ARP address")
        val dstIp = opt[IPv4Addr](
            "dst-ip",
            noshort = true,
            descr = "destination IP/ARP address")
        val proto = opt[IpProtocol](
            "proto",
            noshort = true,
            descr = "IP protocol (TCP, UDP, or ICMP)")
        val tpSrc = opt[Short](
            "src-port",
            noshort = true,
            descr = "transport source port or ICMP type")
        val tpDst = opt[Short](
            "dst-port",
            noshort = true,
            descr = "transport destination port or ICMP code")
        val arp = opt[Boolean](
            "arp",
            default = Some(false),
            noshort = true,
            descr = "src-ip and dst-ip belong to an ARP")
        val arpReply = opt[Boolean](
            "reply",
            default = Some(false),
            noshort = true,
            descr = "tells whether the ARP is a reply")
        val del = opt[Boolean](
            "delete",
            default = Some(false),
            short = 'D',
            descr = "delete the flow")

        dependsOnAll(arpReply, List(arp))
        dependsOnAll(tpSrc, List(proto))
        dependsOnAll(tpDst, List(proto))
        conflicts(arp, List(tpSrc, tpDst, proto))
    }

    implicit class FlowMatchOpt[_](val opt: ScallopOption[_]) extends AnyVal {
        def seeIfDefined(fmatch: FlowMatch) = opt.get foreach { _ => opt match {
            case opts.inPort => fmatch.getInputPortNumber
            case opts.srcMac => fmatch.getEthSrc
            case opts.dstMac => fmatch.getEthDst
            case opts.srcIp => fmatch.getNetworkSrcIP
            case opts.dstIp => fmatch.getNetworkDstIP
            case opts.proto => fmatch.getNetworkProto
            case opts.tpSrc => fmatch.getSrcPort
            case opts.tpDst => fmatch.getDstPort
            case _ =>
        }}
    }

    def run(ctx: DpCtx): Unit = {
        val dp = ctx.getDatapath(opts.dp.get.get)

        val inPort = ctx.getOrCreateDpPort(dp, opts.inPort.get.get)
        val outPort = ctx.getOrCreateDpPort(dp, opts.outPort.get.get)

        val fmatch = buildFlowMatch(inPort)
        val mask = if (dp.supportsMegaflow()) {
            val mask = new FlowMask()
            mask.calculateFor(fmatch)
            mask
        } else null
        val actions = Arrays.asList[FlowAction](FlowActions.output(outPort.getPortNo))

       if (opts.del.get.get) {
            ctx.deleteFlow(dp, fmatch)
        } else {
            ctx.createFlow(dp, fmatch, actions, mask)
        }
    }

    private def buildFlowMatch(inPort: DpPort): FlowMatch = {
        val fmatch = new FlowMatch()
        fmatch.addKey(FlowKeys.inPort(inPort.getPortNo))
        opts.inPort.seeIfDefined(fmatch)
        prepareL2(fmatch)
        fmatch
    }

    private def prepareL2(fmatch: FlowMatch): Unit = {
        fmatch.addKey(FlowKeys.ethernet(
            opts.srcMac.get.getOrElse(NO_MAC).getAddress,
            opts.dstMac.get.getOrElse(NO_MAC).getAddress))
        opts.srcMac.seeIfDefined(fmatch)
        opts.dstMac.seeIfDefined(fmatch)

        fmatch.addKey(FlowKeys.etherType(
            if (opts.arp.get.get) {
                prepareArp(fmatch)
                FlowKeyEtherType.Type.ETH_P_ARP
            } else if (opts.proto.get.isDefined) {
                prepareL3(fmatch)
                FlowKeyEtherType.Type.ETH_P_IP
            } else {
                FlowKeyEtherType.Type.ETH_P_NONE
            }))
    }

    private def prepareL3(fmatch: FlowMatch): Unit = {
        fmatch.addKey(FlowKeys.ipv4(
            opts.srcIp.get.getOrElse(NO_IP),
            opts.dstIp.get.getOrElse(NO_IP),
            opts.proto.get.getOrElse(IpProtocol.ICMP)))

        opts.srcIp.seeIfDefined(fmatch)
        opts.dstIp.seeIfDefined(fmatch)
        opts.proto.seeIfDefined(fmatch)

        prepareL4(fmatch)
    }

    private def prepareL4(fmatch: FlowMatch): Unit = {
        fmatch.addKey(opts.proto.get.get match {
            case IpProtocol.ICMP =>
                FlowKeys.icmp(
                    opts.tpSrc.get.getOrElse(0.toShort).toByte,
                    opts.tpDst.get.getOrElse(0.toShort).toByte)
            case IpProtocol.TCP =>
                FlowKeys.tcp(
                    opts.tpSrc.get.getOrElse(0.toShort).toInt,
                    opts.tpDst.get.getOrElse(0.toShort).toInt)
            case IpProtocol.UDP =>
                FlowKeys.udp(
                    opts.tpSrc.get.getOrElse(0.toShort).toInt,
                    opts.tpDst.get.getOrElse(0.toShort).toInt)
            case _ =>
                throw new Exception("Unrecognized protocol")
        })

        opts.tpSrc.seeIfDefined(fmatch)
        opts.tpDst.seeIfDefined(fmatch)
    }

    private def prepareArp(fmatch: FlowMatch): Unit = {
        fmatch.addKey(FlowKeys.arp(
            NO_MAC.getAddress,
            NO_MAC.getAddress,
            if (opts.arpReply.get.get) 2 else 1,
            opts.srcIp.get.getOrElse(NO_IP).toInt,
            opts.dstIp.get.getOrElse(NO_IP).toInt))

        opts.srcIp.seeIfDefined(fmatch)
        opts.dstIp.seeIfDefined(fmatch)
        opts.proto.seeIfDefined(fmatch)
    }
}

object DatapathCtl extends Subcommand("datapath") with DpCommand {

    descr("datapath operations (create, delete, list, dump)")

    val opts = new ScallopConf(args) {
        val add = opt[String](
            "add",
            noshort = true,
            descr = "add a new datapath")
        val del = opt[String](
            "delete",
            noshort = true,
            descr = "delete a datapath")
        val show = opt[String](
            "show",
            noshort = true,
            descr = "show all the information related to a given datapath")
        val dump = opt[String](
            "dump",
            noshort = true,
            descr = "show all the flows installed for a given datapath")
        val list = opt[Boolean](
            "list",
            noshort = true,
            descr = "list all the installed datapaths")

        requireOne(add, del, show, dump, list)
    }

    def run(ctx: DpCtx): Unit = {
        if (opts.add.get.isDefined) {
            ctx.createDatapath(opts.add.get.get)
            System.out.println("Datapath created successfully")
        }

        if (opts.del.get.isDefined)
            ctx.deleteDatapath(opts.del.get.get)

        if (opts.list.get.isDefined) {
            val dps = ctx.listDatapaths().toList
            if (dps.isEmpty) {
                System.out.println("Could not find any installed datapath")
            } else {
                System.out.println(s"Found ${dps.size} " +
                                   s"datapath${if (dps.size > 1) "s" else ""}")
            }
            dps foreach { dp => System.out.println(s"\t${dp.getName}") }
        }

        if (opts.show.get.isDefined) {
            val dp = ctx.getDatapath(opts.show.get.get)
            val ports = ctx.listDpPorts(dp)
            val stats = dp.getStats()
            System.out.println(
                s"Name: ${dp.getName}\n" +
                s"Index: ${dp.getIndex}" +
                s"Supports megaflows: ${if (dp.supportsMegaflow()) "yes" else "no"}\n" +
                s"Stats:\n" +
                s"\tFlows  ${stats.getFlows}\n" +
                s"\tHits   ${stats.getHits}\n" +
                s"\tLost   ${stats.getLost}\n" +
                s"\tMisses ${stats.getMisses}\n")
            ports foreach { p =>
                System.out.println(
                    s"Port #${p.getPortNo} '${p.getName}' ${p.getType} ${p.getStats}")
            }
        }

        if (opts.dump.get.isDefined) {
            val dp = ctx.getDatapath(opts.show.get.get)
            val flows = ctx.dumpDatapath(dp).toList
                .sortWith(_.getLastUsedMillis < _.getLastUsedMillis)
            System.out.println(s"${flows.size} flow${if (flows.size > 1) "s" else ""}")
            flows foreach { flow =>
                System.out.println("\tFlow")
                flow.toPrettyStrings foreach { f => System.out.println(s"\t\t$f") }
            }
        }
    }
}

object InterfaceCtl extends Subcommand("interface") with DpCommand {
    descr("interface operations (add, delete)")

    val opts = new ScallopConf(args) {
        val dp = opt[String](
            "datapath",
            descr = "the datapath where to create/delete a flow",
            required = true)
        val add = opt[String](
            "add",
            noshort = true,
            descr = "add an interface to a datapath")
        val del = opt[String](
            "delele",
            noshort = true,
            descr = "delete an interface from a datapath")

         requireOne(add, del)
    }

    def run(ctx: DpCtx): Unit = {
        val dp = ctx.getDatapath(opts.dp.get.get)

        if (opts.add.get.isDefined) {
            ctx.createDpPort(dp, opts.add.get.get)
            System.out.println("Interface added to datapath")
        }

        if (opts.del.get.isDefined) {
            ctx.deleteDpPort(dp, opts.del.get.get)
            System.out.println("Interface deleted from datapath")
        }
    }
}

object MmDpctl extends App {
    val SUCCESS = 0
    val FAILURE = 1
    val TIMEOUT = 1

    val opts = new ScallopConf(args) {
        DatapathCtl
        FlowsCtl
        val debug = opt[Boolean](
            "debug",
            default = Some(false),
            noshort = true,
            descr = "enable debug output")
        val timeout = opt[Long](
            "timeout",
            default = Some((3 seconds).toMillis),
            noshort = true,
            descr = "enable debug output")
        printedName = "pistacho"
        footer("Copyright (c) 2015 Midokura SARL, All Rights Reserved.")
    }

    val ret = try {
        val channel = new NetlinkChannelFactory().create(blocking = false)
        val timeout = Duration(opts.timeout.get.get, TimeUnit.MILLISECONDS)
        val ctx = new DpCtx(channel, 2 * 1024 * 1024, timeout)

        opts.subcommand.get.asInstanceOf[DpCommand].run(ctx)
        SUCCESS
    } catch {
        case e: TimeoutException =>
            System.err.println("[mm-dpctl] Failed: Request timeout")
            TIMEOUT
        case NonFatal(e) =>
            var t = e
            while ((t.getCause ne null) && (t.getMessage eq null))
                t = e.getCause
            System.err.println("[mm-dpctl] Failed: " + t.getMessage)
            FAILURE
    }

    System.exit(ret)
}
