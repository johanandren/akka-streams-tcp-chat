package akkastreamchat

import scodec.Codec
import scodec.codecs._

case class Username(name: String)

object Protocol {
  private val username: Codec[Username] =
    utf8_32.as[Username]

  /** Base trait for messages sent from the client to the server. */
  sealed trait ClientCommand

  object ClientCommand {
    case class RequestUsername(name: Username) extends ClientCommand
    case class SendMessage(value: String) extends ClientCommand

    private val codec: Codec[ClientCommand] = discriminated[ClientCommand]
      .by(uint8)
      .typecase(1, username.as[RequestUsername])
      .typecase(2, utf8_32.as[SendMessage])

    val decoder = ScodecGlue.decoder(ClientCommand.codec)
    val encoder = ScodecGlue.encoder(ClientCommand.codec)
  }


  /** Base trait for messages sent from the server to the client. */
  sealed trait ServerCommand
  object ServerCommand {
    case class Welcome(name: Username, banner: String) extends ServerCommand
    case class Alert(text: String) extends ServerCommand
    case class Message(name: Username, text: String) extends ServerCommand
    case class Disconnect(reason: String) extends ServerCommand

    private val codec: Codec[ServerCommand] = discriminated[ServerCommand]
      .by(uint8)
      .typecase(129, (username :: utf8_32).as[Welcome])
      .typecase(130, utf8_32.as[Alert])
      .typecase(131, (username :: utf8_32).as[Message])
      .typecase(132, utf8_32.as[Disconnect])

    val encoder = ScodecGlue.encoder(ServerCommand.codec)
    val decoder = ScodecGlue.decoder(ServerCommand.codec)
  }
}
