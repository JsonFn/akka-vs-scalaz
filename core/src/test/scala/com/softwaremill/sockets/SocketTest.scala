package com.softwaremill.sockets

import java.util.concurrent.{ArrayBlockingQueue, ConcurrentLinkedQueue, TimeUnit}

import org.scalatest.Matchers
import org.scalatest.concurrent.Eventually

import scala.collection.JavaConverters._

trait SocketTest extends Matchers with Eventually {

  def runTest(start: Socket => Unit): Unit = {
    val acceptQueue = new ArrayBlockingQueue[ConnectedSocket](1024)

    val testSocket = new Socket {
      override def accept(timeout: Long): ConnectedSocket = {
        acceptQueue.poll(timeout, TimeUnit.MILLISECONDS)
      }
    }
    class TestConnectedSocket extends ConnectedSocket {
      private val receiveQueue = new ArrayBlockingQueue[String](1024)
      private val sentQueue = new ConcurrentLinkedQueue[String]()

      override def send(msg: String): Unit = sentQueue.offer(msg)
      override def receive(timeout: Long): String = {
        val msg = receiveQueue.poll(timeout, TimeUnit.MILLISECONDS)
        if (msg == "KILL") {
          throw new SocketTerminatedException
        } else {
          msg
        }
      }

      def sent: List[String] = sentQueue.asScala.toList
      def receiveNext(msg: String): Unit = receiveQueue.offer(msg)
    }

    // start listening
    start(testSocket)

    // create 3 clients, send message: should be broadcast
    val s1 = new TestConnectedSocket
    val s2 = new TestConnectedSocket
    val s3 = new TestConnectedSocket
    acceptQueue.put(s1)
    acceptQueue.put(s2)
    acceptQueue.put(s3)

    s1.receiveNext("msg1")
    eventually {
      s1.sent should be(Nil)
      s2.sent should be(List("msg1"))
      s3.sent should be(List("msg1"))
    }

    // create more clients, send more messages
    val s4 = new TestConnectedSocket
    val s5 = new TestConnectedSocket
    acceptQueue.put(s4)
    acceptQueue.put(s5)

    s4.receiveNext("msg2")
    s4.receiveNext("msg3")
    eventually {
      s1.sent should be(List("msg2", "msg3"))
      s2.sent should be(List("msg1", "msg2", "msg3"))
      s3.sent should be(List("msg1", "msg2", "msg3"))
      s4.sent should be(Nil)
      s5.sent should be(List("msg2", "msg3"))
    }

    // terminate one client, send a message
    s5.receiveNext("KILL")
    Thread.sleep(100) // wait for the message to be handled
    s1.receiveNext("msg4")
    eventually {
      s1.sent should be(List("msg2", "msg3"))
      s2.sent should be(List("msg1", "msg2", "msg3", "msg4"))
      s3.sent should be(List("msg1", "msg2", "msg3", "msg4"))
      s4.sent should be(List("msg4"))
      s5.sent should be(List("msg2", "msg3"))
    }
  }
}