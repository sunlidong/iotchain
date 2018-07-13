package jbok.rpc.client

import cats.effect.ConcurrentEffect
import fs2.Scheduler
import jbok.rpc.JsonRPCClient
import jbok.rpc.json.JsonRPCMessage.RequestId
import jbok.rpc.transport.DuplexTransport

object Client {
  def apply[F[_]](transport: DuplexTransport[F, String, String])(
      implicit F: ConcurrentEffect[F], S: Scheduler): JsonRPCClient[F] =
    new JsonRPCClient[F] {
      override def request(id: RequestId, json: String): F[String] =
        transport.request(id, json)

      override def subscribe(maxQueued: Int): fs2.Stream[F, String] = transport.subscribe(maxQueued)

      override def start: F[Unit] = transport.start

      override def stop: F[Unit] = transport.stop
    }
}