/*
 * Copyright (C) 2009-2016 Typesafe Inc. <http://www.typesafe.com>
 */
package play.docs

import akka.stream.scaladsl.StreamConverters
import play.api.libs.MimeTypes
import play.api.mvc._
import play.api.http.{ ContentTypes, HttpEntity }
import play.core.{ PlayVersion, BuildDocHandler }
import play.doc.{ FileRepository, PlayDoc, RenderedPage, PageIndex }

/**
 * Used by the DocumentationApplication class to handle requests for Play documentation.
 * Documentation is located in the given repository - either a JAR file or directly from
 * the filesystem.
 */
class DocumentationHandler(repo: FileRepository, apiRepo: FileRepository) extends BuildDocHandler {

  def this(repo: FileRepository) = this(repo, repo)

  /**
   * This is a def because we want to reindex the docs each time.
   */
  def playDoc = {
    new PlayDoc(repo, repo, "resources", PlayVersion.current, PageIndex.parseFrom(repo, "Home", Some("manual")), "Next")
  }

  val locator: String => String = new Memoise(name =>
    repo.findFileWithName(name).orElse(apiRepo.findFileWithName(name)).getOrElse(name)
  )

  // Method without Scala types. Required by BuildDocHandler to allow communication
  // between code compiled by different versions of Scala
  override def maybeHandleDocRequest(request: AnyRef): AnyRef = {
    this.maybeHandleDocRequest(request.asInstanceOf[RequestHeader])
  }

  /**
   * Handle the given request if it is a request for documentation content.
   */
  def maybeHandleDocRequest(request: RequestHeader): Option[Result] = {

    // Assumes caller consumes result, closing entry
    def sendFileInline(repo: FileRepository, path: String): Option[Result] = {
      repo.handleFile(path) { handle =>
        Results.Ok.sendEntity(HttpEntity.Streamed(
          StreamConverters.fromInputStream(() => handle.is).mapMaterializedValue(_ => handle.close),
          Some(handle.size),
          MimeTypes.forFileName(handle.name).orElse(Some(ContentTypes.BINARY))
        ))
      }
    }

    import play.api.mvc.Results._

    val documentation = """/@documentation/?""".r
    val apiDoc = """/@documentation/api/(.*)""".r
    val wikiResource = """/@documentation/resources/(.*)""".r
    val wikiPage = """/@documentation/([^/]*)""".r

    request.path match {

      case documentation() => Some(Redirect("/@documentation/Home"))
      case apiDoc(page) => Some(
        sendFileInline(apiRepo, "api/" + page)
          .getOrElse(NotFound(views.html.play20.manual(page, None, None, locator)))
      )
      case wikiResource(path) => Some(
        sendFileInline(repo, path).orElse(sendFileInline(apiRepo, path))
          .getOrElse(NotFound("Resource not found [" + path + "]"))
      )
      case wikiPage(page) => Some(
        playDoc.renderPage(page) match {
          case None => NotFound(views.html.play20.manual(page, None, None, locator))
          case Some(RenderedPage(mainPage, None, _)) => Ok(views.html.play20.manual(page, Some(mainPage), None, locator))
          case Some(RenderedPage(mainPage, Some(sidebar), _)) => Ok(views.html.play20.manual(page, Some(mainPage), Some(sidebar), locator))
        }
      )
      case _ => None
    }
  }
}

/**
 * Memoise a function.
 */
class Memoise[-T, +R](f: T => R) extends (T => R) {
  private[this] val cache = scala.collection.mutable.Map.empty[T, R]
  def apply(v: T): R = synchronized { cache.getOrElseUpdate(v, f(v)) }
}
