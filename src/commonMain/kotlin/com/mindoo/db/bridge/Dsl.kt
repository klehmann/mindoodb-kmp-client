package com.mindoo.db.bridge

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * MindooDB expression-language AST mirror (`createViewLanguage()`), just enough
 * for query filters and view column expressions. Produces the same JSON the
 * core engine consumes: `{ kind: "field" | "literal" | "operation", ... }`.
 */
sealed interface Expr {
    fun toJson(): JsonElement
}

private data class FieldExpr(val path: String) : Expr {
    override fun toJson(): JsonElement = buildJsonObject {
        put("kind", "field")
        put("path", path)
    }
}

private data class LiteralExpr(val value: JsonElement) : Expr {
    override fun toJson(): JsonElement = buildJsonObject {
        put("kind", "literal")
        put("value", value)
    }
}

private data class OpExpr(val op: String, val args: List<Expr>) : Expr {
    override fun toJson(): JsonElement = buildJsonObject {
        put("kind", "operation")
        put("op", op)
        put("args", buildJsonArray { args.forEach { add(it.toJson()) } })
    }
}

fun field(path: String): Expr = FieldExpr(path)

fun lit(value: Any?): Expr = LiteralExpr(toJsonPrimitive(value))

private fun asExpr(value: Any?): Expr = if (value is Expr) value else lit(value)

infix fun Expr.eq(other: Any?): Expr = OpExpr("eq", listOf(this, asExpr(other)))
infix fun Expr.neq(other: Any?): Expr = OpExpr("neq", listOf(this, asExpr(other)))
infix fun Expr.gt(other: Any?): Expr = OpExpr("gt", listOf(this, asExpr(other)))
infix fun Expr.gte(other: Any?): Expr = OpExpr("gte", listOf(this, asExpr(other)))
infix fun Expr.lt(other: Any?): Expr = OpExpr("lt", listOf(this, asExpr(other)))
infix fun Expr.lte(other: Any?): Expr = OpExpr("lte", listOf(this, asExpr(other)))
infix fun Expr.and(other: Expr): Expr = OpExpr("and", listOf(this, other))
infix fun Expr.or(other: Expr): Expr = OpExpr("or", listOf(this, other))

fun not(expr: Expr): Expr = OpExpr("not", listOf(expr))
fun allOf(vararg exprs: Expr): Expr = OpExpr("and", exprs.toList())
fun anyOf(vararg exprs: Expr): Expr = OpExpr("or", exprs.toList())
fun exists(path: String): Expr = OpExpr("exists", listOf(field(path)))
fun notExists(path: String): Expr = OpExpr("notExists", listOf(field(path)))
fun contains(path: String, substring: String): Expr = OpExpr("contains", listOf(field(path), lit(substring)))
fun startsWith(path: String, prefix: String): Expr = OpExpr("startsWith", listOf(field(path), lit(prefix)))
fun endsWith(path: String, suffix: String): Expr = OpExpr("endsWith", listOf(field(path), lit(suffix)))

private fun toJsonPrimitive(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Int -> JsonPrimitive(value)
    is Long -> JsonPrimitive(value)
    is Double -> JsonPrimitive(value)
    is Float -> JsonPrimitive(value)
    else -> JsonPrimitive(value.toString())
}

enum class SortDirection(val wire: String) { ASCENDING("ascending"), DESCENDING("descending") }

/** Full-text clause of a query or view (`MindooQueryTextClause`). */
data class TextClause(
    val query: String,
    val fields: List<String>? = null,
    val prefix: Boolean? = null,
    val fuzzy: Boolean? = null,
    val combineWith: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("query", query)
        fields?.let { put("fields", buildJsonArray { it.forEach { f -> add(f) } }) }
        prefix?.let { put("prefix", it) }
        fuzzy?.let { put("fuzzy", it) }
        combineWith?.let { put("combineWith", it) }
    }
}

/** Builder for a `MindooQuery` (ad-hoc summary query). */
class QueryBuilder {
    var filter: Expr? = null
    var text: TextClause? = null
    var limit: Int? = null
    var offset: Int? = null
    var fields: List<String>? = null
    private val sortKeys = mutableListOf<JsonObject>()

    fun sort(field: String, direction: SortDirection = SortDirection.ASCENDING) {
        sortKeys.add(buildJsonObject {
            put("field", field)
            put("direction", direction.wire)
        })
    }

    fun sortByTextScore(direction: SortDirection = SortDirection.DESCENDING) {
        sortKeys.add(buildJsonObject {
            put("special", "textScore")
            put("direction", direction.wire)
        })
    }

    fun build(): JsonObject = buildJsonObject {
        filter?.let { put("filter", it.toJson()) }
        text?.let { put("text", it.toJson()) }
        if (sortKeys.isNotEmpty()) put("sortBy", JsonArray(sortKeys))
        limit?.let { put("limit", it) }
        offset?.let { put("offset", it) }
        fields?.let { list -> put("fields", buildJsonArray { list.forEach { add(it) } }) }
    }
}

fun mindooQuery(block: QueryBuilder.() -> Unit): JsonObject = QueryBuilder().apply(block).build()

/** Builder for a `MindooQueryViewDefinition` (ephemeral virtual view). */
class ViewDefinitionBuilder {
    var filter: Expr? = null
    var text: TextClause? = null
    private val columns = mutableListOf<JsonObject>()

    fun category(
        name: String,
        title: String = name,
        expression: Expr = field(name),
        sorting: SortDirection = SortDirection.ASCENDING,
    ) {
        columns.add(buildJsonObject {
            put("name", name)
            put("title", title)
            put("isCategory", true)
            put("sorting", sorting.wire)
            put("expression", expression.toJson())
        })
    }

    fun column(
        name: String,
        title: String = name,
        expression: Expr = field(name),
        sorting: SortDirection? = null,
        totalMode: String? = null,
        hidden: Boolean = false,
    ) {
        columns.add(buildJsonObject {
            put("name", name)
            put("title", title)
            put("isCategory", false)
            put("isHidden", hidden)
            sorting?.let { put("sorting", it.wire) }
            totalMode?.let { put("totalMode", it) }
            put("expression", expression.toJson())
        })
    }

    fun build(): JsonObject = buildJsonObject {
        filter?.let { put("filter", it.toJson()) }
        text?.let { put("text", it.toJson()) }
        put("columns", JsonArray(columns))
    }
}

fun viewDefinition(block: ViewDefinitionBuilder.() -> Unit): JsonObject =
    ViewDefinitionBuilder().apply(block).build()
