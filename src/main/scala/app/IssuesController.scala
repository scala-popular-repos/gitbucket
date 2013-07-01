package app

import jp.sf.amateras.scalatra.forms._

import service._
import IssuesService._
import util.UsersOnlyAuthenticator

class IssuesController extends IssuesControllerBase
  with IssuesService with RepositoryService with AccountService with LabelsService with MilestonesService
  with UsersOnlyAuthenticator

trait IssuesControllerBase extends ControllerBase {
  self: IssuesService with RepositoryService with LabelsService with MilestonesService
    with UsersOnlyAuthenticator =>

  case class IssueForm(title: String, content: Option[String])
  case class CommentForm(issueId: Int, content: String)

  val form = mapping(
      "title"   -> trim(label("Title", text(required))),
      "content" -> trim(optional(text()))
    )(IssueForm.apply)
  val commentForm = mapping(
      "issueId" -> label("Issue Id", number()),
      "content" -> trim(label("Comment", text(required)))
    )(CommentForm.apply)

  get("/:owner/:repository/issues"){
    searchIssues("all")
  }

  get("/:owner/:repository/issues/assigned/:userName"){
    searchIssues("assigned")
  }

  get("/:owner/:repository/issues/created_by/:userName"){
    searchIssues("created_by")
  }

  get("/:owner/:repository/issues/:id"){
    val owner = params("owner")
    val repository = params("repository")
    val issueId = params("id")

    getIssue(owner, repository, issueId) map {
      issues.html.issue(
          _,
          getComments(owner, repository, issueId.toInt),
          getRepository(owner, repository, baseUrl).get)
    } getOrElse NotFound
  }

  // TODO requires users only and readable repository checking
  get("/:owner/:repository/issues/new")( usersOnly {
    val owner      = params("owner")
    val repository = params("repository")

    getRepository(owner, repository, baseUrl)
      .map (issues.html.create((getCollaborators(owner, repository) :+ owner).sorted,
                               getMilestones(owner, repository), getLabels(owner, repository), _))
      .getOrElse (NotFound)
  })

  // TODO requires users only and readable repository checking
  post("/:owner/:repository/issues/new", form)( usersOnly { form =>
    val owner = params("owner")
    val repository = params("repository")

    redirect("/%s/%s/issues/%d".format(owner, repository,
        createIssue(owner, repository, context.loginAccount.get.userName, form.title, form.content)))
  })

  // TODO Authenticator
  ajaxPost("/:owner/:repository/issues/edit/:id", form){ form =>
    val owner = params("owner")
    val repository = params("repository")
    val issueId = params("id").toInt

    updateIssue(owner, repository, issueId, form.title, form.content)
    redirect("/%s/%s/issues/_data/%d".format(owner, repository, issueId))
  }

  // TODO requires users only and readable repository checking
  post("/:owner/:repository/issue_comments/new", commentForm)( usersOnly { form =>
    val owner = params("owner")
    val repository = params("repository")
    val action = params.get("action") filter { action =>
      updateClosed(owner, repository, form.issueId, if(action == "close") true else false) > 0
    }

    redirect("/%s/%s/issues/%d#comment-%d".format(owner, repository, form.issueId,
        createComment(owner, repository, context.loginAccount.get.userName, form.issueId, form.content, action)))
  })

  // TODO Authenticator, repository checking
  ajaxPost("/:owner/:repository/issue_comments/edit/:id", commentForm){ form =>
    val commentId = params("id").toInt

    updateComment(commentId, form.content)
    redirect("/%s/%s/issue_comments/_data/%d".format(params("owner"), params("repository"), commentId))
  }

  // TODO Authenticator
  ajaxGet("/:owner/:repository/issues/_data/:id"){
    getIssue(params("owner"), params("repository"), params("id")) map { x =>
      params.get("dataType") collect {
        case t if t == "html" => issues.html.editissue(
            x.title, x.content, x.issueId, x.userName, x.repositoryName)
      } getOrElse {
        contentType = formats("json")
        org.json4s.jackson.Serialization.write(
            Map("title"   -> x.title,
                "content" -> view.Markdown.toHtml(x.content getOrElse "No description given.",
                    getRepository(x.userName, x.repositoryName, baseUrl).get, false, true, true)
            ))
      }
    } getOrElse NotFound
  }

  // TODO Authenticator
  ajaxGet("/:owner/:repository/issue_comments/_data/:id"){
    getComment(params("id")) map { x =>
      params.get("dataType") collect {
        case t if t == "html" => issues.html.editcomment(
            x.content, x.commentId, x.userName, x.repositoryName)
      } getOrElse {
        contentType = formats("json")
        org.json4s.jackson.Serialization.write(
            Map("content" -> view.Markdown.toHtml(x.content,
                getRepository(x.userName, x.repositoryName, baseUrl).get, false, true, true)
            ))
      }
    } getOrElse NotFound
  }

  private def searchIssues(filter: String) = {
    val owner      = params("owner")
    val repository = params("repository")
    val userName   = if(filter != "all") Some(params("userName")) else None
    val sessionKey = "%s/%s/issues".format(owner, repository)

    val page = try {
      val i = params.getOrElse("page", "1").toInt
      if(i <= 0) 1 else i
    } catch {
      case e: NumberFormatException => 1
    }

    // retrieve search condition
    val condition = if(request.getQueryString == null){
      session.get(sessionKey).getOrElse(IssueSearchCondition()).asInstanceOf[IssueSearchCondition]
    } else IssueSearchCondition(request)

    session.put(sessionKey, condition)

    getRepository(owner, repository, baseUrl).map { repositoryInfo =>
      issues.html.list(
        searchIssue(owner, repository, condition, filter, userName, (page - 1) * IssueLimit, IssueLimit),
        page,
        getLabels(owner, repository),
        getMilestones(owner, repository).filter(_.closedDate.isEmpty),
        countIssue(owner, repository, condition.copy(state = "open"), filter, userName),
        countIssue(owner, repository, condition.copy(state = "closed"), filter, userName),
        countIssue(owner, repository, condition, "all", None),
        context.loginAccount.map(x => countIssue(owner, repository, condition, "assigned", Some(x.userName))),
        context.loginAccount.map(x => countIssue(owner, repository, condition, "created_by", Some(x.userName))),
        countIssueGroupByLabels(owner, repository, condition, filter, userName),
        condition,
        filter,
        repositoryInfo,
        isWritable(owner, repository, context.loginAccount))

    } getOrElse NotFound
  }

}
