/*
 * FXGL - JavaFX Game Library. The MIT License (MIT).
 * Copyright (c) AlmasB (almaslvl@gmail.com).
 * See LICENSE for details.
 */

package com.almasb.fxgl.tools.dialogues

import com.almasb.fxgl.animation.Interpolators
import com.almasb.fxgl.core.math.FXGLMath
import com.almasb.fxgl.cutscene.dialogue.*
import com.almasb.fxgl.cutscene.dialogue.DialogueNodeType.*
import com.almasb.fxgl.dsl.animationBuilder
import com.almasb.fxgl.dsl.getAppHeight
import com.almasb.fxgl.dsl.getAppWidth
import com.almasb.fxgl.dsl.runOnce
import com.almasb.fxgl.tools.dialogues.ui.FXGLContextMenu
import com.almasb.fxgl.logging.Logger
import javafx.beans.property.SimpleBooleanProperty
import javafx.collections.ListChangeListener
import javafx.collections.MapChangeListener
import javafx.geometry.Point2D
import javafx.scene.Group
import javafx.scene.effect.Glow
import javafx.scene.input.MouseButton
import javafx.scene.layout.Pane
import javafx.scene.shape.Circle
import javafx.scene.transform.Scale
import javafx.util.Duration

/**
 *
 *
 * @author Almas Baimagambetov (almaslvl@gmail.com)
 */
class DialoguePane(graph: DialogueGraph = DialogueGraph()) : Pane() {

    companion object {
        private val log = Logger.get<DialoguePane>()

        private val branch: (DialogueNode) -> NodeView = { BranchNodeView(it) }
        private val end: (DialogueNode) -> NodeView = { EndNodeView(it) }
        private val start: (DialogueNode) -> NodeView = { StartNodeView(it) }
        private val function: (DialogueNode) -> NodeView = { FunctionNodeView(it) }
        private val text: (DialogueNode) -> NodeView = { TextNodeView(it) }
        private val subdialogue: (DialogueNode) -> NodeView = { SubDialogueNodeView(it) }
        private val choice: (DialogueNode) -> NodeView = { ChoiceNodeView(it) }

        val nodeConstructors = linkedMapOf<DialogueNodeType, () -> DialogueNode>(
                TEXT to { TextNode("") },
                CHOICE to { ChoiceNode("") },
                BRANCH to { BranchNode("") },
                FUNCTION to { FunctionNode("") },
                END to { EndNode("") },
                START to { StartNode("") },
                SUBDIALOGUE to { SubDialogueNode("") }
        )

        val nodeViewConstructors = linkedMapOf<DialogueNodeType, (DialogueNode) -> NodeView>(
                TEXT to text,
                CHOICE to choice,
                BRANCH to branch,
                FUNCTION to function,
                END to end,
                START to start,
                SUBDIALOGUE to subdialogue
        )
    }

    private val contentRoot = Group()

    private var selectedOutLink: OutLinkPoint? = null

    var graph = graph
        private set

    private val views = Group()
    private val edgeViews = Group()
    private val nodeViews = Group()

    val isDirtyProperty = SimpleBooleanProperty(false)

    private val scale = Scale()

    private val dragScale = 1.35

    private var mouseX = 0.0
    private var mouseY = 0.0

    private val mouseGestures = MouseGestures(contentRoot)

    init {
        setPrefSize(getAppWidth().toDouble(), getAppHeight().toDouble())
        style = "-fx-background-color: gray"

        contentRoot.children.addAll(
                edgeViews, views, nodeViews
        )

        contentRoot.transforms += scale

        setOnScroll {
            val scaleFactor = if (it.deltaY < 0) 0.95 else 1.05

            scale.x *= scaleFactor
            scale.y *= scaleFactor
        }

        children.addAll(contentRoot)

        // start and end

        createNode(StartNodeView(), 50.0, getAppHeight() / 2.0)
        createNode(EndNodeView(), getAppWidth() - 370.0, getAppHeight() / 2.0)

        initContextMenu()

        setOnMouseMoved {
            mouseX = it.sceneX
            mouseY = it.sceneY
        }

        setOnMouseDragged {
            if (mouseGestures.isDragging || it.button != MouseButton.PRIMARY)
                return@setOnMouseDragged

            contentRoot.translateX += (it.sceneX - mouseX) * dragScale
            contentRoot.translateY += (it.sceneY - mouseY) * dragScale

            mouseX = it.sceneX
            mouseY = it.sceneY
        }

        initGraphListeners()
    }

    private fun initContextMenu() {
        val contextMenu = FXGLContextMenu()

        nodeConstructors
                .filter { it.key != START }
                .forEach { (type, ctor) ->
                    contextMenu.addItem(type.toString()) {
                        graph.addNode(ctor())
                    }
                }

        setOnContextMenuRequested {
            if (it.target !== this)
                return@setOnContextMenuRequested

            contextMenu.show(contentRoot, it.sceneX, it.sceneY)
        }
    }

    private fun initGraphListeners() {
        graph.nodes.addListener { c: MapChangeListener.Change<out Int, out DialogueNode> ->
            if (c.wasAdded()) {
                val node = c.valueAdded

                onAdded(node)

            } else if (c.wasRemoved()) {
                val node = c.valueRemoved

                onRemoved(node)
            }
        }

        graph.edges.addListener { c: ListChangeListener.Change<out DialogueEdge> ->
            while (c.next()) {
                if (c.wasAdded()) {
                    c.addedSubList.forEach { onAdded(it) }
                } else if (c.wasRemoved()) {
                    c.removed.forEach { onRemoved(it) }
                }
            }
        }
    }

    private fun onAdded(node: DialogueNode) {
        val p = contentRoot.sceneToLocal(mouseX, mouseY)

        onAdded(node, p.x, p.y)
    }

    private fun onAdded(node: DialogueNode, x: Double, y: Double) {
        log.debug("Added node: $node")

        isDirtyProperty.value = true

        val nodeViewConstructor = nodeViewConstructors[node.type] ?: throw IllegalArgumentException("View constructor for ${node.type} does not exist")
        val nodeView = nodeViewConstructor(node)

        addNodeView(nodeView, x, y)
    }

    private fun onRemoved(node: DialogueNode) {
        log.debug("Removed node: $node")

        isDirtyProperty.value = true

        val nodeView = getNodeView(node)

        // so that user does not accidentally press it again
        nodeView.closeButton.isVisible = false

        animationBuilder()
                .duration(Duration.seconds(0.56))
                .interpolator(Interpolators.EXPONENTIAL.EASE_OUT())
                .onFinished(Runnable { nodeViews.children -= nodeView })
                .scale(nodeView)
                .from(Point2D(1.0, 1.0))
                .to(Point2D.ZERO)
                .buildAndPlay()
    }

    private fun onAdded(edge: DialogueEdge) {
        log.debug("Added edge: $edge")

        isDirtyProperty.value = true

        val (outPoint, inPoint) = if (edge is DialogueChoiceEdge) {
            getNodeView(edge.source).outPoints.find { it.choiceOptionID == edge.optionID }!! to getNodeView(edge.target).inPoint!!
        } else {
            getNodeView(edge.source).outPoints.first() to getNodeView(edge.target).inPoint!!
        }

        outPoint.connect(inPoint)

        val edgeView = EdgeView(edge, outPoint, inPoint)

        edgeViews.children.add(edgeView)
    }

    private fun onRemoved(edge: DialogueEdge) {
        log.debug("Removed edge: $edge")

        isDirtyProperty.value = true

        val edgeView = getEdgeView(edge)

        val p1 = Point2D(edgeView.startX, edgeView.startY)
        val p2 = Point2D(edgeView.controlX1, edgeView.controlY1)
        val p3 = Point2D(edgeView.controlX2, edgeView.controlY2)
        val p4 = Point2D(edgeView.endX, edgeView.endY)

        val group = Group()
        group.effect = Glow(0.7)

        val numSegments = 350

        for (t in 0..numSegments) {
            val delay = if (graph.findNodeID(edgeView.source.owner.node) == -1) t else (numSegments - t)

            val p = FXGLMath.bezier(p1, p2, p3, p4, t / numSegments.toDouble())

            val c = Circle(p.x, p.y, 2.0, edgeView.stroke)

            group.children += c

            animationBuilder()
                    .interpolator(Interpolators.BOUNCE.EASE_OUT())
                    .delay(Duration.millis(delay * 2.0))
                    .duration(Duration.seconds(0.35))
                    .fadeOut(c)
                    .buildAndPlay()
        }

        views.children += group

        runOnce({ views.children -= group }, Duration.seconds(7.0))

        edgeView.source.disconnect()

        edgeViews.children -= edgeView
    }

    private fun getNodeView(node: DialogueNode): NodeView {
        return nodeViews.children
                .map { it as NodeView }
                .find { it.node === node }
                ?: throw IllegalArgumentException("No view found for node $node")
    }

    private fun getEdgeView(edge: DialogueEdge): EdgeView {
        val optionID = if (edge is DialogueChoiceEdge) edge.optionID else -1

        return edgeViews.children
                .map { it as EdgeView }
                .find { it.source.owner.node === edge.source && it.optionID == optionID && it.target.owner.node === edge.target }
                ?: throw IllegalArgumentException("No edge view found for edge $edge")
    }

    private fun createNode(nodeView: NodeView, x: Double, y: Double) {
        graph.addNode(nodeView.node)

        addNodeView(nodeView, x, y)
    }

    private fun addNodeView(nodeView: NodeView, x: Double, y: Double) {
        nodeView.relocate(x, y)

        attachMouseHandler(nodeView)

        nodeViews.children.add(nodeView)

        // START node cannot be removed
        if (nodeView.node.type == START) {
            nodeView.closeButton.isVisible = false
        }
    }

    private fun attachMouseHandler(nodeView: NodeView) {
        mouseGestures.makeDraggable(nodeView)

        nodeView.closeButton.setOnMouseClicked {
            graph.removeNode(nodeView.node)
        }

        nodeView.outPoints.forEach { outPoint ->
            attachMouseHandler(outPoint)
        }

        nodeView.outPoints.addListener { c: ListChangeListener.Change<out OutLinkPoint> ->
            while (c.next()) {
                c.addedSubList.forEach { outPoint ->
                    attachMouseHandler(outPoint)
                }
            }
        }

        nodeView.inPoint?.let { inPoint ->
            attachMouseHandler(inPoint)
        }
    }

    private fun attachMouseHandler(outPoint: OutLinkPoint) {
        outPoint.setOnMouseClicked {
            if (it.button == MouseButton.PRIMARY) {
                selectedOutLink = outPoint
            } else {
                if (outPoint.isConnected) {
                    disconnectOutLink(outPoint)
                }
            }
        }
    }

    private fun attachMouseHandler(inPoint: InLinkPoint) {
        inPoint.setOnMouseClicked {
            if (it.button == MouseButton.PRIMARY) {
                selectedOutLink?.let { outPoint ->

                    if (outPoint.isConnected) {
                        disconnectOutLink(outPoint)
                    }

                    if (outPoint.choiceOptionID != -1) {
                        graph.addChoiceEdge(outPoint.owner.node, outPoint.choiceOptionID, inPoint.owner.node)
                    } else {
                        graph.addEdge(outPoint.owner.node, inPoint.owner.node)
                    }

                    // reset selection
                    selectedOutLink = null
                }
            }
        }
    }

    private fun disconnectOutLink(outPoint: OutLinkPoint) {
        outPoint.other?.let { inPoint ->
            if (outPoint.choiceOptionID != -1) {
                graph.removeChoiceEdge(outPoint.owner.node, outPoint.choiceOptionID, inPoint.owner.node)
            } else {
                graph.removeEdge(outPoint.owner.node, inPoint.owner.node)
            }
        }
    }

    fun save(): SerializableGraph {
        isDirtyProperty.value = false

        val serializedGraph = DialogueGraphSerializer.toSerializable(graph)

        nodeViews.children.map { it as NodeView }.forEach {
            serializedGraph.uiMetadata[graph.findNodeID(it.node)] = SerializablePoint2D(it.layoutX, it.layoutY)
        }

        return serializedGraph
    }

    fun load(serializedGraph: SerializableGraph) {
        graph = DialogueGraphSerializer.fromSerializable(serializedGraph)

        nodeViews.children.clear()
        edgeViews.children.clear()

        graph.nodes.forEach { (id, node) ->
            val x = serializedGraph.uiMetadata[id]?.x ?: 100.0
            val y = serializedGraph.uiMetadata[id]?.y ?: 100.0

            onAdded(node, x, y)
        }

        graph.edges.forEach { edge ->
            onAdded(edge)
        }

        initGraphListeners()

        isDirtyProperty.value = false
    }
}