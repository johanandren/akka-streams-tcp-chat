/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */package akkastreamchat

import akka.NotUsed
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Framing
import akka.util.ByteString
import scodec.Attempt
import scodec.Codec
import scodec.bits.BitVector

import scala.util.Failure
import scala.util.Success
import scala.util.Try

object ScodecGlue {

  private val MaxMessageLength = 2048

  def decoder[T](codec: Codec[T]): Flow[ByteString, Try[T], NotUsed] =
    Flow[ByteString].via(Framing.simpleFramingProtocolDecoder(MaxMessageLength))
    .map { frame =>
      codec.decode(BitVector(frame.toByteBuffer)) match {
        case Attempt.Successful(t) => Success(t.value)
        case Attempt.Failure(cause) => Failure(new RuntimeException(s"Unparseable command: $cause"))
      }
    }

  def encoder[T](codec: Codec[T]): Flow[T, ByteString, NotUsed] =
    Flow[T].map { t =>
      codec.encode(t) match {
        case Attempt.Successful(bytes) => ByteString(bytes.toByteBuffer)
        case Attempt.Failure(error) => throw new RuntimeException(s"Failed to encode $t: $error")
      }

    }.via(Framing.simpleFramingProtocolEncoder(MaxMessageLength))

}
