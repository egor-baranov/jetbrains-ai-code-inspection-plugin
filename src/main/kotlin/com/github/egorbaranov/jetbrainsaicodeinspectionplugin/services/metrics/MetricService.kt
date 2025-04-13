package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.metrics

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project
import org.jdom.Element

@State(
    name = "MetricService",
    storages = [Storage("MetricServiceState.xml")]
)
@Service(Service.Level.PROJECT)
class MetricService : PersistentStateComponent<Element> {

    private val metrics: MutableList<Metric> = mutableListOf()

    override fun getState(): Element {
        return Element("metrics").apply {
            metrics.forEach { metric ->
                addContent(Element("metric").apply {
                    setAttribute("id", metric.id.name)
                    setAttribute("timestamp", metric.timestamp)

                    // Save parameters
                    metric.params.takeIf { it.isNotEmpty() }?.let { params ->
                        addContent(Element("parameters").apply {
                            params.forEach { (key, value) ->
                                addContent(Element("param").apply {
                                    setAttribute("key", key)
                                    setAttribute("value", value)
                                })
                            }
                        })
                    }
                })
            }
        }
    }

    override fun loadState(state: Element) {
        metrics.clear()
        state.getChildren("metric").forEach { metricElement ->
            try {
                val id = Metric.MetricID.valueOf(metricElement.getAttributeValue("id"))
                val timestamp = metricElement.getAttributeValue("timestamp")

                val params = mutableMapOf<String, String>()
                metricElement.getChild("parameters")?.let { paramsElement ->
                    paramsElement.getChildren("param").forEach { paramElement ->
                        paramElement.getAttribute("key")?.let { keyAttr ->
                            paramElement.getAttribute("value")?.let { valueAttr ->
                                params[keyAttr.value] = valueAttr.value
                            }
                        }
                    }
                }

                metrics.add(Metric(id, params, timestamp))
            } catch (e: Exception) {
                // Handle invalid entries
//                project.messageBus.syncPublisher(MetricsNotifier.TOPIC)
//                    .onMetricError("Failed to load metric: ${e.message}")
            }
        }
    }

    fun collect(metric: Metric) {
        metrics.add(metric)
    }

    fun collect(id: Metric.MetricID, params: Map<String, String> = mapOf()) {
        metrics.add(Metric.new(id, params))
    }

    fun error(e: Exception) = collect(
        id = Metric.MetricID.ERROR,
        params = mapOf(Metric.MetricParams.EXCEPTION.str to e.message.orEmpty())
    )

    fun error(e: Throwable) = collect(
        id = Metric.MetricID.ERROR,
        params = mapOf(Metric.MetricParams.EXCEPTION.str to e.message.orEmpty())
    )

    fun getMetrics(filter: Metric.MetricID? = null): List<Metric> {
        return metrics.filter { filter == null || it.id == filter }
    }

    fun clearData() {
        metrics.clear()
    }

    companion object {
        fun getInstance(project: Project): MetricService {
            return project.service()
        }
    }
}