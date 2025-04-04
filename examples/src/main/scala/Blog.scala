package examples

import krop.all.*
import scalatags.Text.all.*

object Blog {
  case class Post(id: String, title: String, content: String)

  private val posts = List(
    Post("1", "First Post", "Welcome to my Krop blog!"),
    Post("2", "Example", "Interactive web apps made easy")
  )

  val postRoute =
    Route(
      Request.get(Path / "post" :? Query("id", Param.string)),
      Response.ok(Entity.scalatags)
    )

  val index =
    html(
      body(
        h1("My Blog"),
        div(cls := "posts")(
          posts.map { post =>
            div(cls := "post")(
              h2(a(href := s"/post?id=${post.id}", post.title)),
              p(post.content)
            )
          }
        )
      )
    )

  val indexHandler =
    Route(Request.get(Path.root), Response.ok(Entity.scalatags))
      .handle(() => index)

  val postHandler =
    postRoute.handle { id =>
      posts.find(_.id == id) match {
        case Some(post) =>
          div(
            h1(post.title),
            p(post.content),
            a(href := "/", "Back to home")
          )
        case None =>
          div("Post not found")
      }
    }
}

@main def runBlog() =
  ServerBuilder.default
    .withApplication(
      Blog.indexHandler
        .orElse(Blog.postHandler)
        .orElseNotFound
    )
    .run()