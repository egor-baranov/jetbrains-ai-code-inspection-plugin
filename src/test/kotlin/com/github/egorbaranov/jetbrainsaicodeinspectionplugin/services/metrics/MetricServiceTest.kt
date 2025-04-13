package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.metrics

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jdom.Element
import java.time.Instant

class MetricServiceTest : BasePlatformTestCase() {

    private lateinit var metricService: MetricService

    override fun setUp() {
        super.setUp()
        metricService = MetricService.getInstance(project).apply {
            loadState(Element("empty"))
        }
    }

    fun `test getInstance returns same service`() {
        val service1 = MetricService.getInstance(project)
        val service2 = MetricService.getInstance(project)
        assertTrue(service1 === service2)
    }

    fun `test collect metric with object`() {
        val metric = Metric(
            Metric.MetricID.ERROR,
            mapOf("key" to "value"),
            Instant.now().toString()
        )

        metricService.collect(metric)
        val metrics = metricService.getMetrics()

        assertEquals(1, metrics.size)
        assertEquals(metric.id, metrics.first().id)
        assertEquals(metric.params, metrics.first().params)
    }

    fun `test collect metric with id and params`() {
        val params = mapOf("param1" to "value1", "param2" to "value2")

        metricService.collect(Metric.MetricID.ERROR, params)
        val metrics = metricService.getMetrics()

        assertEquals(1, metrics.size)
        assertEquals(Metric.MetricID.ERROR, metrics.first().id)
        assertEquals(params, metrics.first().params)
    }

    fun `test error collection with exception`() {
        val exception = Exception("Test exception")

        metricService.error(exception)
        val metrics = metricService.getMetrics(Metric.MetricID.ERROR)

        assertEquals(1, metrics.size)
        assertEquals("Test exception", metrics.first().params["exception"])
    }

    fun `test error collection with throwable`() {
        val throwable = Throwable("Test throwable")

        metricService.error(throwable)
        val metrics = metricService.getMetrics(Metric.MetricID.ERROR)

        assertEquals(1, metrics.size)
        assertEquals("Test throwable", metrics.first().params["exception"])
    }

    fun `test getMetrics with filter`() {
        metricService.collect(Metric.MetricID.ERROR)
        metricService.collect(Metric.MetricID.EXECUTE)

        val errorMetrics = metricService.getMetrics(Metric.MetricID.ERROR)
        val allMetrics = metricService.getMetrics()

        assertEquals(1, errorMetrics.size)
        assertEquals(2, allMetrics.size)
    }

    fun `test state serialization and deserialization`() {
        val metric1 = Metric(Metric.MetricID.ERROR, mapOf("key1" to "val1"), "timestamp1")
        val metric2 = Metric(Metric.MetricID.EXECUTE, emptyMap(), "timestamp2")

        metricService.collect(metric1)
        metricService.collect(metric2)

        val stateElement = metricService.state

        // Create new service instance for test isolation
        val newService = MetricService().apply {
            loadState(stateElement)
        }

        val loadedMetrics = newService.getMetrics()
        assertEquals(2, loadedMetrics.size)
        assertTrue(loadedMetrics.any { it.id == metric1.id && it.params == metric1.params })
        assertTrue(loadedMetrics.any { it.id == metric2.id && it.params == metric2.params })
    }

    fun `test state serialization with parameters`() {
        val params = mapOf("param1" to "value1", "param2" to "value2")
        metricService.collect(Metric.MetricID.ERROR, params)

        val stateElement = metricService.state
        val metricElement = stateElement.children.first()
        val paramsElement = metricElement.getChild("parameters")

        assertNotNull(paramsElement)
        assertEquals(2, paramsElement.children.size)
        assertEquals("value1", paramsElement.children.find {
            it.getAttributeValue("key") == "param1"
        }?.getAttributeValue("value"))
    }

    fun `test load invalid state skips bad entries`() {
        val invalidState = Element("metrics").apply {
            addContent(Element("metric")) // Missing attributes
            addContent(Element("metric").apply {
                setAttribute("id", "INVALID_ID")
                setAttribute("timestamp", "123")
            })
        }

        metricService.loadState(invalidState)
        assertEquals(0, metricService.getMetrics().size)
    }

    fun `test empty state handling`() {
        metricService.loadState(Element("empty"))
        assertEquals(0, metricService.getMetrics().size)
    }

    fun `test state persistence cycle`() {
        // Clear any existing metrics
        metricService.loadState(Element("empty"))

        val initialMetrics = listOf(
            Metric(Metric.MetricID.ERROR, mapOf("test" to "data"), "ts1"),
            Metric(Metric.MetricID.EXECUTE, emptyMap(), "ts2")
        )

        initialMetrics.forEach { metricService.collect(it) }

        val savedState = metricService.state
        val newService = MetricService().apply {
            loadState(savedState)
        }

        val loadedMetrics = newService.getMetrics()
        assertEquals(2, loadedMetrics.size)
        assertTrue(loadedMetrics.containsAll(initialMetrics))
    }
}