/*
 * Scala (https://www.scala-lang.org)
 *
 * Copyright EPFL and Lightbend, Inc.
 *
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package scala.tools.nsc

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable, mutable.ArrayDeque, mutable.ListBuffer
import scala.reflect.io.{File, Path}
import scala.util.chaining._

/** Sorts the global phasesSet according to SubComponent constraints.
 */
trait PhaseAssembly {
  this: Global =>

  /** Called by Global#computePhaseDescriptors to compute phase order.
   *
   *  The phases to assemble are provided by `phasesSet`, which must contain
   *  an `initial` phase. If no phase is `terminal`, then `global.terminal` is added.
   */
  def computePhaseAssembly(): List[SubComponent] = {
    require(phasesSet.exists(phase => phase.initial || phase.phaseName == DependencyGraph.Parser), "Missing initial phase")
    if (!phasesSet.exists(phase => phase.terminal || phase.phaseName == DependencyGraph.Terminal))
      if (phasesSet.add(terminal))
        reporter.warning(NoPosition, "Added default terminal phase")
    val graph = DependencyGraph(phasesSet)
    for (n <- settings.genPhaseGraph.valueSetByUser; d <- settings.outputDirs.getSingleOutput if !d.isVirtual)
      DependencyGraph.graphToDotFile(graph, Path(d.file) / File(s"$n.dot"))
    graph.compilerPhaseList()
  }
}

/** A graph with the given number of vertices.
 *
 *  Each vertex is labeled with its phase name.
 */
class DependencyGraph(order: Int, start: String, val components: Map[String, SubComponent]) {
  import DependencyGraph.{FollowsNow, Weight}

  //private final val debugging = false

  /** For ith vertex, its outgoing edges. */
  private val adjacency: Array[List[Edge]] = Array.fill(order)(Nil)

  /** Directed edge. */
  private case class Edge(from: Int, to: Int, weight: Weight)

  // phase names and their vertex index
  private val nodeCount = new AtomicInteger
  private val nodes = mutable.HashMap.empty[String, Int]
  private val names = Array.ofDim[String](order)

  /** Add the edge between named phases, where `to` follows `from`.
   */
  private def addEdge(from: String, to: String, weight: Weight): Unit = {
    def getNode(name: String): Int = {
      def installName(name: String, n: Int): Unit =
        if (n >= names.length) throw new FatalError(names.mkString(s"Extra name $name; names [",",","]"))
        else names(n) = name
      nodes.getOrElseUpdate(name, nodeCount.getAndIncrement().tap(installName(name, _)))
    }
    val v = getNode(from)
    val w = getNode(to)
    adjacency(v).find(e => e.from == v && e.to == w) match {
      case None =>
        adjacency(v) ::= Edge(from = v, to = w, weight)
      case Some(e) if weight == FollowsNow => // retain runsRightAfter if there is a competing constraint
        adjacency(v) = Edge(from = v, to = w, weight) :: adjacency(v).filterNot(e => e.from == v && e.to == w)
      case _ =>
    }
  }

  // input must be acyclic and only one FollowsNow edge is allowed between a pair of vertices
  private def validate(): Unit = {
    def checkFollowsNow(v: Int): Unit =
      adjacency(v).foldLeft(-1) { (w, e) =>
        if (e.weight != FollowsNow) w
        else if (w == -1) e.to
        else throw new FatalError(s"Phases ${names(w)} and ${names(e.to)} both immediately follow ${names(v)}")
      }
    val seen = Array.ofDim[Boolean](order)
    val onPath = Array.ofDim[Boolean](order)
    val stack = mutable.Stack.empty[(Int, List[Edge])] // a vertex and list of edges remaining to follow
    def walk(): Unit = {
      nodes(start).tap { start =>
        stack.push(start -> adjacency(start))
      }
      while (!stack.isEmpty) {
        val (v, edges) = stack.pop()
        if (!seen(v)) {
          checkFollowsNow(v)
          seen(v) = true
        }
        onPath(v) = true
        edges match {
          case Edge(_, to, _) :: edges =>
            if (onPath(to)) {
              var path = v :: to :: Nil
              while (path.head != to)
                path ::= stack.pop()._1
              throw new FatalError(s"Phases form a cycle: ${path.map(names(_)).mkString(" -> ")}")
            }
            stack.push(v -> edges)
            if (!seen(to))
              stack.push(to -> adjacency(to))
          case _ => onPath(v) = false
        }
      }
    }
    walk()
  }

  def compilerPhaseList(): List[SubComponent] = if (order == 1) List(components(start)) else {
    // distance from source to each vertex
    val distance = Array.fill[Int](order)(Int.MinValue)

    // incoming edge terminating in each vertex for the current path
    val edgeTo = Array.ofDim[Edge](order)

    // whether vertex is on the queue
    val enqueued = Array.ofDim[Boolean](order)

    // vertices to process
    val queue = mutable.Queue.empty[Int]

    def enqueue(v: Int): Unit = if (!enqueued(v)) queue.enqueue(v).tap(_ => enqueued(v) = true)

    def dequeue(): Int = queue.dequeue().tap(v => enqueued(v) = false)

    //def namedEdge(e: Edge): String = if (e == null) "[no edge]" else s"${names(e.from)} ${if (e.weight == FollowsNow) "=" else "-"}> ${names(e.to)}"

    /** Remove a vertex from the queue and check outgoing edges:
     *  if an edge improves (increases) the distance at the terminal,
     *  record that as the new incoming edge and enqueue that vertex
     *  to propagate updates.
     */
    def relax(): Unit = {
      nodes(start).tap { start =>
        distance(start) = 0
        enqueue(start)
      }
      while (!queue.isEmpty) {
        val v = dequeue()
        //if (debugging) println(s"deq ${names(v)}")
        for (e <- adjacency(v)) {
          val w = e.to
          val e2 = edgeTo(w)
          if (e.weight == FollowsNow && e2 != null && e2.weight == FollowsNow && e.from != e2.from)
            throw new FatalError(s"${names(w)} cannot follow right after both ${names(e.from)} and ${names(e2.from)}")
          if (distance(w) < distance(v) + e.weight) {
            distance(w) = distance(v) + e.weight
            edgeTo(w) = e
            enqueue(w)
            //if (debugging) println(s"update ${namedEdge(e)} dist = ${distance(w)}, enq ${names(w)}")
          }
        }
      }
      //if (debugging) edgeTo.foreach(e => println(namedEdge(e)))
    }
    /** Put the vertices in a linear order.
     *
     *  Partition by "level" or distance from start.
     *  Partition the level into "anchors" that follow a node in the previous level, and "followers".
     *  Starting with the "ends", which are followers without a follower in the level,
     *  construct paths back to the anchors. The anchors are sorted by name only.
     */
    def traverse(): List[SubComponent] = {
      def componentOf(i: Int) = components(names(i))
      def sortComponents(c: SubComponent, d: SubComponent): Boolean =
        /*c.internal && !d.internal ||*/ c.phaseName.compareTo(d.phaseName) < 0
      def sortVertex(i: Int, j: Int): Boolean = sortComponents(componentOf(i), componentOf(j))

      distance.zipWithIndex.groupBy(_._1).toList.sortBy(_._1)
      .flatMap { case (d, dis) =>
        val vs = dis.map { case (_, i) => i }
        val (anchors, followers) = vs.partition(v => edgeTo(v) == null || distance(edgeTo(v).from) != d)
        //if (debugging) println(s"d=$d, anchors=${anchors.toList.map(n => names(n))}, followers=${followers.toList.map(n => names(n))}")
        if (followers.isEmpty)
          anchors.toList.map(componentOf).sortWith(sortComponents)
        else {
          // find phases which are not the source of an edgeTo, then construct paths at this level distance
          val froms = followers.map(v => edgeTo(v).from).toSet
          val ends = followers.iterator.filterNot(froms).toList
          val followed: Array[ArrayDeque[Int]] = anchors.map(ArrayDeque(_))
          def drill(v: Int, path: List[Int]): Unit =
            edgeTo(v) match {
              case e if e != null && distance(e.from) == d => drill(e.from, v :: path)
              case _ => followed.find(_.apply(0) == v).foreach(deque => path.foreach(deque.append))
            }
          ends.foreach(drill(_, Nil))
          followed.sortWith((p, q) => sortVertex(p(0), q(0))).toList.flatten.map(componentOf)
        }
      }
    }
    validate()
    relax()
    traverse()
  }
}
object DependencyGraph {

  type Weight = Int
  final val FollowsNow = 0
  final val Follows = 1

  final val Parser = "parser"
  final val Terminal = "terminal"

  /** Create a DependencyGraph from the given phases. The graph must be acyclic.
   *
   *  A component must be declared as "initial".
   *  If no phase is "initial" but a phase is named "parser", it is taken as initial.
   *  If no phase is "terminal" but a phase is named "terminal", it is taken as terminal.
   *  Empty constraints are ignored.
   */
  def apply(phases: Iterable[SubComponent]): DependencyGraph = {
    val start = phases.find(_.initial)
      .orElse(phases.find(_.phaseName == Parser))
      .getOrElse(throw new AssertionError("Missing initial component"))
    val end = phases.find(_.terminal)
      .orElse(phases.find(_.phaseName == Terminal))
      .getOrElse(throw new AssertionError("Missing terminal component"))
    new DependencyGraph(phases.size, start.phaseName, phases.map(p => p.phaseName -> p).toMap).tap { graph =>
      for (p <- phases) {
        val name = p.phaseName
        require(!name.isEmpty, "Phase name must be non-empty.")
        for (after <- p.runsRightAfter if !after.isEmpty)
          graph.addEdge(after, name, FollowsNow)
        for (after <- p.runsAfter.filterNot(p.runsRightAfter.contains) if !after.isEmpty)
          graph.addEdge(after, name, Follows)
        for (before <- p.runsBefore if !before.isEmpty)
          graph.addEdge(name, before, Follows)
        if (p != start && p != end)
          if (p.runsRightAfter.find(!_.isEmpty).isEmpty && p.runsAfter.find(!_.isEmpty).isEmpty)
            graph.addEdge(start.phaseName, name, Follows)
        if (p != end || p == end && p == start)
          if (!p.runsBefore.contains(end.phaseName))
            graph.addEdge(name, end.phaseName, Follows)
      }
    }
  }

  /** Emit a graphviz dot file for the graph.
   *  Plug-in supplied phases are marked as green nodes and hard links are marked as blue edges.
   */
  def graphToDotFile(graph: DependencyGraph, file: File): Unit = {
    def color(hex: String) = s""" [color="#$hex"]"""
    val sb = ListBuffer.empty[String]
    sb.addOne("digraph G {")
    for (edges <- graph.adjacency; e <- edges)
      sb.addOne(s"${graph.names(e.from)} -> ${graph.names(e.to)}${if (e.weight == FollowsNow) color("0000ff") else ""}")
    for (n <- graph.names)
      sb.addOne(s"${n}${if (graph.components(n).internal) "" else color("00ff00")}")
    sb.addOne("}")
    file.printlnAll(sb.toList: _*)
  }
}
