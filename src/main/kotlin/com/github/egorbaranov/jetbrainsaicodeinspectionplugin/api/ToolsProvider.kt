package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.api

import com.google.gson.Gson
import com.google.gson.JsonObject

object ToolsProvider {

    private val gson = Gson()

    fun createTools(): List<JsonObject> = listOfNotNull(
        createTool(
            name = "add_inspection",
            description = "Create new code inspection representing fix or improvement for all analyzed files",
            parameters = JsonObject().apply {
                addProperty("type", "object")
                add("properties", JsonObject().apply {
                    add(
                        "description",
                        createStringSchema("Short description of the issue that should be less than 80 symbols")
                    )
                    add("fix_prompt", createStringSchema("Detailed instructions to fix the issue in a prompt format"))
                })
                add("required", gson.toJsonTree(listOf("description", "fix_prompt")))
            }
        ),
        createTool(
            name = "apply_inspection",
            description = "Apply existing inspection to current files",
            parameters = JsonObject().apply {
                addProperty("type", "object")
                add("properties", JsonObject().apply {
                    add("inspection_id", createStringSchema("ID of existing inspection to apply"))
                })
                add("required", gson.toJsonTree(listOf("inspection_id")))
            }
        ),
        createTool(
            name = "request_context",
            description = "Request additional context needed for analysis",
            parameters = JsonObject().apply {
                addProperty("type", "object")
                add("properties", JsonObject().apply {
                    add("context_type", createStringSchema("Type of context needed (e.g., imports, dependencies)"))
                })
                add("required", gson.toJsonTree(listOf("context_type")))
            }
        )
    )

    private fun createTool(name: String, description: String, parameters: JsonObject): JsonObject {
        return JsonObject().apply {
            addProperty("type", "function")
            add("function", JsonObject().apply {
                addProperty("name", name)
                addProperty("description", description)
                add("parameters", parameters)
            })
        }
    }

    private fun createStringSchema(description: String): JsonObject {
        return JsonObject().apply {
            addProperty("type", "string")
            addProperty("description", description)
        }
    }
}