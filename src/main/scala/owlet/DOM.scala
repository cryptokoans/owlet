package us.oyanglul.owlet

import cats.Later
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import monix.reactive.Observable
import org.scalajs.dom._
import monix.reactive.subjects.Var
import org.scalajs.dom.raw.HTMLElement
import scala.util.Try
import cats.syntax.traverse._
import cats.instances.list._

object DOM {
  // ==Input==
  def string(name: String, default: String): Owlet[String] = {
    val state = Var(default)
    val input = createInput(
      name,
      "text",
      default,
      e => state := e.target.asInstanceOf[html.Input].value
    )
    Owlet(Later(List(input)), state)
  }

  def number(name: String, default: Double): Owlet[Double] = {
    val state = Var(default)
    val input = createInput(name, "number", default, e => {
      val value = e.target.asInstanceOf[html.Input].value
      Try(value.toDouble).foreach(state := _)
    })
    input.step = "any"
    Owlet(Later(List(input)), state)
  }

  def numberSlider(
      name: String,
      min: Double,
      max: Double,
      default: Double
  ): Owlet[Double] = {
    val state = Var(default)
    val input = createInput(name, "range", default, e => {
      val value = e.target.asInstanceOf[html.Input].value
      Try(value.toDouble.toInt).foreach(state := _)
    })
    input.step = "any"
    input.min = min.toString
    input.max = max.toString
    Owlet(Later(List(input)), state)
  }

  def int(name: String, default: Int): Owlet[Int] = {
    val state = Var(default)
    val input = createInput(name, "number", default, e => {
      val value = e.target.asInstanceOf[html.Input].value
      Try(value.toDouble.toInt).foreach(state := _)
    })
    Owlet(Later(List(input)), state)
  }

  def boolean(name: String, default: Boolean): Owlet[Boolean] = {
    val sink = Var(default)
    val input = document.createElement("input").asInstanceOf[html.Input]
    input.`type` = "checkbox"
    input.name = name
    input.className = "owlet-input-" + normalize(name)
    input.checked = default
    input.onchange = e => sink := e.target.asInstanceOf[html.Input].checked
    Owlet(Later(List(input)), sink)
  }

  def intSlider(
      name: String,
      min: Int,
      max: Int,
      default: Int
  ): Owlet[Int] = {
    val state = Var(default)
    val input = createInput(name, "range", default, e => {
      val value = e.target.asInstanceOf[html.Input].value
      Try(value.toDouble.toInt).foreach(state := _)
    })
    input.step = "1"
    input.min = min.toString
    input.max = max.toString
    Owlet(Later(List(input)), state)
  }

  private def createInput[A](
      n: String,
      t: String,
      default: A,
      transform: Event => Unit,
  ) = {
    val input: html.Input =
      document.createElement("input").asInstanceOf[html.Input]
    input.`type` = t
    input.name = n
    input.className = "owlet-input " + normalize(n)
    input.defaultValue = default.toString
    input.oninput = e => transform(e)
    input
  }

  /**
    * Select
    */
  def select(
      name: String,
      source: Observable[Map[String, String]],
      default: String
  ): Owlet[String] = {
    val el = document.createElement("select").asInstanceOf[html.Select]
    source.foreach(options => {
      el.innerHTML = options.map { (kv: (String, String)) =>
        val op = document.createElement("option").asInstanceOf[html.Option]
        op.text = kv._1
        op.value = kv._2
        op.defaultSelected = (kv._1 == default)
        op.outerHTML
      }.mkString
    })
    val sink = Var(default)
    el.onchange = e => sink := e.target.asInstanceOf[html.Select].value
    Owlet(Later(List(el)), sink)
  }

  /**
    * button emit `default` value immediatly and emit `pressed` value every time it's clicked
    */
  def button[A](name: String, default: A, pressed: A) = {
    val el = document.createElement("button").asInstanceOf[html.Button]
    el.appendChild(document.createTextNode(name))
    val sink = Var(default)
    el.onmousedown = _ => sink := pressed
    el.onmouseup = _ => sink := default
    Owlet(Later(List(el)), sink)
  }

  /**
    * Container
    *
    * wrap nodes in `Owlet` into container element `div`, `label` etc
    * style of div can reactive from a stream of `className`
    */
  def createContainer[A, Tag <: HTMLElement](
      tag: String,
      inner: Owlet[A],
      className: Observable[Seq[String]] = Observable.empty,
      id: Option[String] = None
  ): Owlet[A] = {
    val wrapped = inner.nodes.map { nodes =>
      val el = document.createElement(tag).asInstanceOf[Tag]
      id.map(el.id = _)
      className.foreach(c => el.className = c.mkString(" "))
      nodes.foreach(el.appendChild)
      List(el)
    }
    Owlet(wrapped, inner.signal)
  }

  def div[A](
      inner: Owlet[A],
      className: Observable[Seq[String]] = Observable.empty,
      id: Option[String] = None
  ) = {
    createContainer[A, html.Div]("div", inner, className, id)
  }

  def ul[A](
      inner: Owlet[A],
      className: Observable[Seq[String]] = Observable.empty,
      id: Option[String] = None
  ) = {
    createContainer[A, html.UList]("ul", inner, className, id)
  }

  def li[A](
      inner: Owlet[A],
      className: Observable[Seq[String]] = Observable.empty,
      id: Option[String] = None
  ) = {
    createContainer[A, html.LI]("li", inner, className, id)
  }

  def label[A](inner: Owlet[A], name: String): Owlet[A] = {
    Owlet(
      inner.nodes.map { nodes =>
        val el = document.createElement("label").asInstanceOf[html.Label]
        el.appendChild(document.createTextNode(name))
        nodes.foreach(el.appendChild)
        List(el)
      },
      inner.signal
    )
  }

  def text(content: String, name: String): Owlet[Nothing] = {
    Owlet(Later(List(document.createTextNode(content))), Observable.empty)
  }

  /** Spreadsheet like fx
    * create a new Owlet with existing Owlets with a formula
    */
  def fx[A, B](formula: List[A] => B, input: List[Owlet[A]]): Owlet[B] = {
    val div: html.Div = document.createElement("div").asInstanceOf[html.Div]
    val sink = input.sequence.signal.map(formula)
    sink.foreach(a => div.textContent = a.toString)
    Owlet(Later(List(div)), sink)
  }

  def output[A](
      input: Owlet[A],
      classNames: Observable[Seq[String]] = Var(Nil)
  ) = {
    val div = document.createElement("div").asInstanceOf[html.Div]
    classNames.foreach(c => div.className = c.mkString(" "))
    div.className += " owlet-output"
    input.signal.foreach(v => div.innerHTML = v.toString)
    input.nodes.map(_ :+ div)
  }

  /**
    * Render
    */
  def render[A](owlet: Owlet[A], selector: String) =
    for {
      _ <- Task {
        owlet.nodes.value
          .foreach(document.querySelector(selector).appendChild)
      }
      _ <- Task(owlet.signal.subscribe)
    } yield ()

  def renderOutput[A](owlet: Owlet[A], selector: String) = Task(
    output(owlet).value
      .foreach(document.querySelector(selector).appendChild)
  )

  private def normalize(s: String) = s.replaceAll(" ", "-").toLowerCase
}
