/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, derived from Akka.
 */

/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.stream.io

import java.net.InetSocketAddress

import scala.collection.immutable.Queue
import scala.concurrent.duration._

import org.apache.pekko
import pekko.actor._
import pekko.io.IO
import pekko.io.Tcp
import pekko.io.Tcp.ConnectionClosed
import pekko.io.Tcp.ResumeReading
import pekko.stream.testkit._
import pekko.testkit.SocketUtil.temporaryServerAddress
import pekko.testkit.TestProbe
import pekko.util.ByteString

object TcpHelper {
  case class ClientWrite(bytes: ByteString) extends NoSerializationVerificationNeeded
  case class ClientRead(count: Int, readTo: ActorRef) extends NoSerializationVerificationNeeded
  case class ClientClose(cmd: Tcp.CloseCommand) extends NoSerializationVerificationNeeded
  case class ReadResult(bytes: ByteString) extends NoSerializationVerificationNeeded

  // FIXME: Workaround object just to force a ResumeReading that will poll for a possibly pending close event
  // See https://github.com/akka/akka/issues/16552
  // remove this and corresponding code path once above is fixed
  case class PingClose(requester: ActorRef)

  case object WriteAck extends Tcp.Event

  def testClientProps(connection: ActorRef): Props =
    Props(new TestClient(connection)).withDispatcher("pekko.test.stream-dispatcher")
  def testServerProps(address: InetSocketAddress, probe: ActorRef): Props =
    Props(new TestServer(address, probe)).withDispatcher("pekko.test.stream-dispatcher")

  class TestClient(connection: ActorRef) extends Actor {
    connection ! Tcp.Register(self, keepOpenOnPeerClosed = true, useResumeWriting = false)

    var queuedWrites = Queue.empty[ByteString]
    var writePending = false

    var toRead = 0
    var readBuffer = ByteString.empty
    var readTo: ActorRef = context.system.deadLetters

    var closeAfterWrite: Option[Tcp.CloseCommand] = None

    // FIXME: various close scenarios
    def receive = {
      case ClientWrite(bytes) if !writePending =>
        writePending = true
        connection ! Tcp.Write(bytes, WriteAck)
      case ClientWrite(bytes) =>
        queuedWrites = queuedWrites :+ bytes
      case WriteAck if queuedWrites.nonEmpty =>
        val (next, remaining) = queuedWrites.dequeue
        queuedWrites = remaining
        connection ! Tcp.Write(next, WriteAck)
      case WriteAck =>
        writePending = false
        closeAfterWrite match {
          case Some(cmd) => connection ! cmd
          case None      =>
        }
      case ClientRead(count, requester) =>
        readTo = requester
        toRead = count
        connection ! Tcp.ResumeReading
      case Tcp.Received(bytes) =>
        readBuffer ++= bytes
        if (readBuffer.size >= toRead) {
          readTo ! ReadResult(readBuffer)
          readBuffer = ByteString.empty
          toRead = 0
          readTo = context.system.deadLetters
        } else connection ! Tcp.ResumeReading
      case PingClose(requester) =>
        readTo = requester
        connection ! ResumeReading
      case c: ConnectionClosed =>
        readTo ! c
        if (!c.isPeerClosed) context.stop(self)
      case ClientClose(cmd) =>
        if (!writePending) connection ! cmd
        else closeAfterWrite = Some(cmd)
    }

  }

  case object ServerClose

  class TestServer(serverAddress: InetSocketAddress, probe: ActorRef) extends Actor {
    import context.system
    IO(Tcp) ! Tcp.Bind(self, serverAddress, pullMode = true)
    var listener: ActorRef = _

    def receive = {
      case b @ Tcp.Bound(_) =>
        listener = sender()
        listener ! Tcp.ResumeAccepting(1)
        probe ! b
      case Tcp.Connected(_, _) =>
        val handler = context.actorOf(testClientProps(sender()))
        listener ! Tcp.ResumeAccepting(1)
        probe ! handler
      case ServerClose =>
        listener ! Tcp.Unbind
        context.stop(self)
    }

  }

}

trait TcpHelper { this: TcpSpec =>
  import pekko.stream.io.TcpHelper._

  class Server(val address: InetSocketAddress = temporaryServerAddress()) {
    val serverProbe = TestProbe()
    val serverRef = system.actorOf(testServerProps(address, serverProbe.ref))
    serverProbe.expectMsgType[Tcp.Bound]

    def waitAccept(): ServerConnection = new ServerConnection(serverProbe.expectMsgType[ActorRef])
    def close(): Unit = serverRef ! ServerClose
  }

  class ServerConnection(val connectionActor: ActorRef) {
    val connectionProbe = TestProbe()

    def write(bytes: ByteString): Unit = connectionActor ! ClientWrite(bytes)

    def read(count: Int): Unit = connectionActor ! ClientRead(count, connectionProbe.ref)

    def waitRead(): ByteString = connectionProbe.expectMsgType[ReadResult].bytes
    def confirmedClose(): Unit = connectionActor ! ClientClose(Tcp.ConfirmedClose)
    def close(): Unit = connectionActor ! ClientClose(Tcp.Close)
    def abort(): Unit = connectionActor ! ClientClose(Tcp.Abort)

    def expectClosed(expected: ConnectionClosed): Unit = expectClosed(_ == expected)

    def expectClosed(p: (ConnectionClosed) => Boolean, max: Duration = 3.seconds): Unit = {
      connectionActor ! PingClose(connectionProbe.ref)
      connectionProbe.fishForMessage(max) {
        case c: ConnectionClosed if p(c) => true
        case _                           => false
      }
    }

    def expectTerminated(): Unit = {
      connectionProbe.watch(connectionActor)
      connectionProbe.expectTerminated(connectionActor)
    }
  }

  class TcpReadProbe() {
    val subscriberProbe = TestSubscriber.manualProbe[ByteString]()
    lazy val tcpReadSubscription = subscriberProbe.expectSubscription()

    def read(count: Int): ByteString = {
      var result = ByteString.empty
      while (result.size < count) {
        tcpReadSubscription.request(1)
        result ++= subscriberProbe.expectNext()
      }
      result
    }

    def close(): Unit = tcpReadSubscription.cancel()
  }

  class TcpWriteProbe() {
    val publisherProbe = TestPublisher.manualProbe[ByteString]()
    lazy val tcpWriteSubscription = publisherProbe.expectSubscription()
    var demand = 0L

    def write(bytes: ByteString): Unit = {
      if (demand == 0) demand += tcpWriteSubscription.expectRequest()
      tcpWriteSubscription.sendNext(bytes)
      demand -= 1
    }

    def close(): Unit = tcpWriteSubscription.sendComplete()
  }

}
