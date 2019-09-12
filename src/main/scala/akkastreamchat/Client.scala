package akkastreamchat

import akka.Done
import akka.actor.ActorSystem
import akka.routing.SeveralRoutees
import akka.stream.ActorMaterializer
import akka.stream.Materializer
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.Tcp
import akkastreamchat.Protocol.ClientCommand
import akkastreamchat.Protocol.ClientCommand.SendMessage
import akkastreamchat.Protocol.ServerCommand

import scala.concurrent.duration._
import scala.concurrent.Await
import scala.concurrent.Future
import scala.io.StdIn
import scala.util.Failure
import scala.util.Success
import scala.util.Try

object Client {

  def main(args: Array[String]): Unit ={
    implicit val system = ActorSystem("client")
    implicit val materializer = ActorMaterializer()
    import system.dispatcher
    try {
      val host = args(0)
      val port = args(1).toInt
      val username = args(2)
      run(host, port, Username(username)).foreach { _ =>
        // disconnected, kill client
        System.exit(0)
      }
    } catch {
      case th: Throwable =>
        println(th.getMessage)
        th.printStackTrace()
        println("Usage: Server [host] [port] [username]")
        System.exit(1)
    }
  }

  private val commandsIn = // unfoldResource deals with blocking non-streamed things
    Source.unfoldResource[String, Iterator[String]](
      () => Iterator.continually(StdIn.readLine("> ")),
      iterator => Some(iterator.next()),
      _  => ()
    ).map(ClientCommand.SendMessage)

  private val serverCommandToString = Flow[Try[ServerCommand]].map {
    case Success(command) =>
      command match {
        case ServerCommand.Welcome(user, banner) =>
          s"Logged in as ${user.name} \n$banner"
        case ServerCommand.Alert(msg) => msg
        case ServerCommand.Message(user, msg) =>
          s"${user.name}: $msg"
        case ServerCommand.Disconnect(why) =>
          s"Server disconnected because: $why"
      }
    case Failure(ex) =>
      s"Error parsing server command: ${ex.getMessage}"
  }

  def run(host: String, port: Int, username: Username)(implicit system: ActorSystem, materialiser: Materializer): Future[Done] = {
    import system.dispatcher

    val in =
      Source.single(ClientCommand.RequestUsername(username))
        .concat(commandsIn)
      .via(ClientCommand.encoder)

    val out = ServerCommand.decoder
      .takeWhile(!_.toOption.exists(_.isInstanceOf[ServerCommand.Disconnect]), inclusive = true)
      .via(serverCommandToString)
      .concat(Source.single("Disconnected from server"))
      .toMat(Sink.foreach(println))(Keep.right)

    val (connected, done) = in.viaMat(Tcp(system)
      .outgoingConnection(host, port))(Keep.right)
      .toMat(out)(Keep.both).run()

    connected.foreach { connection =>
        println(s"Connected to ${connection.remoteAddress.getHostString}:${connection.remoteAddress.getPort}")
    }

    done
  }

}
