/*
 * Copyright 2021 Typelevel
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

package feral.lambda
package cloudformation

import cats._
import cats.syntax.all._
import feral.lambda.cloudformation.CloudFormationRequestType._
import io.circe._
import io.circe.syntax._
import org.http4s.EntityEncoder
import org.http4s.Method.PUT
import org.http4s.circe._
import org.http4s.client.Client
import org.http4s.client.dsl.Http4sClientDsl

trait CloudFormationCustomResource[F[_], Input, Output] {
  def createResource(input: Input): F[HandlerResponse[Output]]
  def updateResource(
      input: Input,
      physicalResourceId: PhysicalResourceId): F[HandlerResponse[Output]]
  def deleteResource(
      input: Input,
      physicalResourceId: PhysicalResourceId): F[HandlerResponse[Output]]
}

object CloudFormationCustomResource {
  private[cloudformation] implicit def jsonEncoder[F[_]]: EntityEncoder[F, Json] =
    jsonEncoderWithPrinter(Printer.noSpaces.copy(dropNullValues = true))

  def apply[F[_]: MonadThrow, Input, Output: Encoder](
      client: Client[F],
      handler: CloudFormationCustomResource[F, Input, Output])(
      implicit
      env: LambdaEnv[F, CloudFormationCustomResourceRequest[Input]]): F[Option[INothing]] = {
    val http4sClientDsl = new Http4sClientDsl[F] {}
    import http4sClientDsl._

    env.event.flatMap { event =>
      ((event.RequestType, event.PhysicalResourceId) match {
        case (CreateRequest, None) => handler.createResource(event.ResourceProperties)
        case (UpdateRequest, Some(id)) => handler.updateResource(event.ResourceProperties, id)
        case (DeleteRequest, Some(id)) => handler.deleteResource(event.ResourceProperties, id)
        case (other, _) => illegalRequestType(other.toString)
      }).attempt
        .map(_.fold(exceptionResponse(event)(_), successResponse(event)(_)))
        .flatMap { resp => client.successful(PUT(resp.asJson, event.ResponseURL)) }
        .as(None)
    }
  }

  private def illegalRequestType[F[_]: ApplicativeThrow, A](other: String): F[A] =
    (new IllegalArgumentException(
      s"unexpected CloudFormation request type `$other`"): Throwable).raiseError[F, A]

  private[cloudformation] def exceptionResponse(req: CloudFormationCustomResourceRequest[_])(
      ex: Throwable): CloudFormationCustomResourceResponse =
    CloudFormationCustomResourceResponse(
      Status = RequestResponseStatus.Failed,
      Reason = Option(ex.getMessage),
      PhysicalResourceId = req.PhysicalResourceId,
      StackId = req.StackId,
      RequestId = req.RequestId,
      LogicalResourceId = req.LogicalResourceId,
      Data = JsonObject(
        "StackTrace" -> Json.arr(stackTraceLines(ex).map(Json.fromString): _*)).asJson
    )

  private[cloudformation] def successResponse[Output: Encoder](
      req: CloudFormationCustomResourceRequest[_])(
      res: HandlerResponse[Output]): CloudFormationCustomResourceResponse =
    CloudFormationCustomResourceResponse(
      Status = RequestResponseStatus.Success,
      Reason = None,
      PhysicalResourceId = Option(res.physicalId),
      StackId = req.StackId,
      RequestId = req.RequestId,
      LogicalResourceId = req.LogicalResourceId,
      Data = res.data.asJson
    )

  // TODO figure out how to include more of the stack trace, up to a total response size of 4096 bytes
  private def stackTraceLines(throwable: Throwable): List[String] =
    List(throwable.toString)

}
