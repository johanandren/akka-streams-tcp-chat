# Small TCP Chat Server and Client built with Akka Streams.

Inspired by the FS2 sample by [Michael Pilquist](https://github.com/mpilquist): https://github.com/functional-streams-for-scala/fs2-chat
but probably not _exactly_ 1:1 with the functionality of that.

Not the greatest sample of backpressured async streams, but still quite fun little app covering quite a bit
of Akka Stream APIs (and a little [Scodec](http://scodec.org) which made encoding/decoding the protocol a breeze)

Running the server: 
`sbt "runMain akkastreamchat.Server 127.0.0.1 2842"`

Running the client connecting to the server:
`sbt "runMain akkastreamchat.Client 127.0.0.1 2842 tiger"`
`sbt "runMain akkastreamchat.Client 127.0.0.1 2842 scott"`

Client commands:
`text` send to all connected users
`/users` lists logged in users
`/quit` disconnect and terminate client

