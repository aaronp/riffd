package riff.rest

import riff.{Request, Response}

case class RiffRaftRestClient() {

  def send(request: Request) = ???

  def send(response: Response) = ???

  def send(either: Either[Request, Response]) = either match {
    case Left(request) => send(request)
    case Right(response) => send(response)
  }
}
