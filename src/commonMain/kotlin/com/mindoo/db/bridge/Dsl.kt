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
 * The MindooDB view language (`createViewLanguage()` in MindooDB) for queries,
 * view filters and column formulas. Every function builds the same JSON AST the
 * engine evaluates (`{ kind: "field" | "literal" | "operation" | "if" | "let" | … }`),
 * so anything TypeScript apps write with `v.…` can be written here.
 *
 * Arguments typed `Any?` accept an [Expr] or a plain value (String, number,
 * Boolean, null, a [JsonElement]), which becomes a literal.
 */
sealed interface Expr {
    fun toJson(): JsonElement
}

private class NodeExpr(private val json: JsonObject) : Expr {
    override fun toJson(): JsonElement = json
}

private fun node(kind: String, block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): Expr =
    NodeExpr(buildJsonObject { put("kind", kind); block() })

private fun op(name: String, vararg args: Any?): Expr = opList(name, args.toList())

private fun opList(name: String, args: List<Any?>): Expr = node("operation") {
    put("op", name)
    put("args", buildJsonArray { args.forEach { add(asExpr(it).toJson()) } })
}

/** An expression given as raw AST JSON (escape hatch for nodes added to MindooDB later). */
fun rawExpr(json: JsonObject): Expr = NodeExpr(json)

private fun asExpr(value: Any?): Expr = if (value is Expr) value else lit(value)

private fun toJsonPrimitive(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Iterable<*> -> JsonArray(value.map { toJsonPrimitive(it) })
    else -> JsonPrimitive(value.toString())
}

// ----- values -----

/** A literal value. */
fun lit(value: Any?): Expr = node("literal") { put("value", toJsonPrimitive(value)) }
fun str(value: String): Expr = lit(value)
fun num(value: Number): Expr = lit(value)
fun bool(value: Boolean): Expr = lit(value)

/** A document field; dots address nested values (`"meta.owner"`). */
fun field(path: String): Expr = node("field") { put("path", path) }

/** The value an earlier column of the same view computed (view columns only). */
fun value(path: String): Expr = node("value") { put("path", path) }

/** The origin (source) of the entry being evaluated; differs per source in a multi-database view. */
fun origin(): Expr = node("origin")

/** A field of the parent row (query `include` filters only); the parent's ID is [parentDocId]. */
fun parent(path: String): Expr = node("parent") { put("path", path) }

// ----- document metadata -----

fun docId(): Expr = op("docId")
/** The parent row's document ID (query `include` filters: `field("orderId") eq parentDocId()`). */
fun parentDocId(): Expr = op("parentDocId")
fun createdAt(): Expr = op("createdAt")
fun lastModifiedAt(): Expr = op("lastModifiedAt")
fun decryptionKeyId(): Expr = op("decryptionKeyId")
/** True when a trusted server has witnessed the document's latest change. */
fun isWitnessed(): Expr = op("isWitnessed")
fun isAwaitingWitness(): Expr = op("isAwaitingWitness")
fun attachmentNames(): Expr = op("attachmentNames")
fun attachmentLengths(): Expr = op("attachmentLengths")
fun attachmentCount(): Expr = op("attachmentCount")

// ----- view tree counters (view columns only) -----

fun childCount(): Expr = op("childCount")
fun childCategoryCount(): Expr = op("childCategoryCount")
fun childDocumentCount(): Expr = op("childDocumentCount")
fun descendantCount(): Expr = op("descendantCount")
fun descendantCategoryCount(): Expr = op("descendantCategoryCount")
fun descendantDocumentCount(): Expr = op("descendantDocumentCount")
fun siblingCount(): Expr = op("siblingCount")

// ----- arithmetic -----

fun add(left: Any?, right: Any?): Expr = op("add", left, right)
fun sub(left: Any?, right: Any?): Expr = op("sub", left, right)
fun mul(left: Any?, right: Any?): Expr = op("mul", left, right)
/** Division; `null` when dividing by zero. */
fun div(left: Any?, right: Any?): Expr = op("div", left, right)
fun mod(left: Any?, right: Any?): Expr = op("mod", left, right)

operator fun Expr.plus(other: Any?): Expr = add(this, other)
operator fun Expr.minus(other: Any?): Expr = sub(this, other)
operator fun Expr.times(other: Any?): Expr = mul(this, other)
operator fun Expr.div(other: Any?): Expr = div(this, other)
operator fun Expr.rem(other: Any?): Expr = mod(this, other)

// ----- comparison and logic -----

infix fun Expr.eq(other: Any?): Expr = op("eq", this, other)
infix fun Expr.neq(other: Any?): Expr = op("neq", this, other)
/** Numbers compare numerically, dates by time, other values as strings. */
infix fun Expr.gt(other: Any?): Expr = op("gt", this, other)
infix fun Expr.gte(other: Any?): Expr = op("gte", this, other)
infix fun Expr.lt(other: Any?): Expr = op("lt", this, other)
infix fun Expr.lte(other: Any?): Expr = op("lte", this, other)
infix fun Expr.and(other: Any?): Expr = op("and", this, other)
infix fun Expr.or(other: Any?): Expr = op("or", this, other)

fun not(expr: Any?): Expr = op("not", expr)
fun allOf(vararg exprs: Any?): Expr = opList("and", exprs.toList())
fun anyOf(vararg exprs: Any?): Expr = opList("or", exprs.toList())

// ----- text -----

/** Joins the values, skipping null and empty ones. */
fun concat(vararg parts: Any?): Expr = opList("concat", parts.toList())
fun lower(value: Any?): Expr = op("lower", value)
fun upper(value: Any?): Expr = op("upper", value)
fun trim(value: Any?): Expr = op("trim", value)
/** The first `by` characters, or the text before the first occurrence of `by` when it is a string. */
fun left(value: Any?, by: Any?): Expr = op("left", value, by)
/** The last `by` characters, or the text after the last occurrence of `by` when it is a string. */
fun right(value: Any?, by: Any?): Expr = op("right", value, by)
/** Case-insensitive substring test. */
fun contains(value: Any?, substring: Any?): Expr = op("contains", value, substring)
fun startsWith(value: Any?, prefix: Any?): Expr = op("startsWith", value, prefix)
fun endsWith(value: Any?, suffix: Any?): Expr = op("endsWith", value, suffix)
fun contains(path: String, substring: String): Expr = contains(field(path), substring)
fun startsWith(path: String, prefix: String): Expr = startsWith(field(path), prefix)
fun endsWith(path: String, suffix: String): Expr = endsWith(field(path), suffix)
/** Joins non-empty parts with `\` into a category path (sub-categories in a view). */
fun pathJoin(vararg parts: Any?): Expr = opList("pathJoin", parts.toList())

// ----- conversion and null handling -----

/** Number, or null when the value is not numeric (`toNumber` in TypeScript). */
fun asNumber(value: Any?): Expr = op("number", value)
fun asString(value: Any?): Expr = op("string", value)
fun asBoolean(value: Any?): Expr = op("boolean", value)
/** The first value that is not null or empty. */
fun coalesce(vararg values: Any?): Expr = opList("coalesce", values.toList())
fun exists(value: Expr): Expr = op("exists", value)
fun notExists(value: Expr): Expr = op("notExists", value)
fun exists(path: String): Expr = exists(field(path))
fun notExists(path: String): Expr = notExists(field(path))

enum class DatePart(val wire: String) { Year("year"), Month("month"), Day("day"), Quarter("quarter") }

/** Year (number), month and day (zero-padded text, "04") or quarter ("Q1"…"Q4") of a date or ISO timestamp, in UTC. */
fun datePart(value: Any?, part: DatePart): Expr = node("operation") {
    put("op", "datePart")
    put("args", buildJsonArray { add(asExpr(value).toJson()) })
    put("part", part.wire)
}

// ----- encrypted and JSON fields -----

/** The plaintext of an `_encrypted` field (key from `<field>_key`, or [key]). Needs a full scan in queries. */
fun decryptField(field: String, key: Any? = null): Expr = node("decrypt") {
    put("field", field)
    if (key != null) put("key", asExpr(key).toJson())
}

/** An encrypted JSON field, parsed; [path] selects a nested value. */
fun decryptJson(field: String, path: String? = null, key: Any? = null): Expr = node("decrypt") {
    put("field", field)
    put("json", true)
    path?.let { put("path", it) }
    if (key != null) put("key", asExpr(key).toJson())
}

/** A plain field holding JSON text, parsed; [path] selects a nested value. */
fun json(field: String, path: String? = null): Expr = node("json") {
    put("field", field)
    path?.let { put("path", it) }
}

// ----- branching and variables -----

/**
 * `ifElse(cond1, value1, cond2, value2, …, default)` like Domino's `@If`: the
 * value of the first true condition, otherwise the default.
 */
fun ifElse(condition: Any?, whenTrue: Any?, vararg moreBranchesAndDefault: Any?): Expr {
    val args = listOf(condition, whenTrue) + moreBranchesAndDefault.toList()
    require(args.size >= 3 && args.size % 2 == 1) {
        "ifElse expects condition/value pairs followed by one default value"
    }
    var result = asExpr(args.last())
    for (i in args.size - 3 downTo 0 step 2) {
        val cond = asExpr(args[i]).toJson()
        val then = asExpr(args[i + 1]).toJson()
        val otherwise = result.toJson()
        result = node("if") { put("condition", cond); put("whenTrue", then); put("whenFalse", otherwise) }
    }
    return result
}

/** References to the names bound by [let]. */
class LetRefs internal constructor(private val names: Set<String>) {
    operator fun get(name: String): Expr {
        require(name in names) { "No binding named $name" }
        return node("variable") { put("name", name) }
    }
}

/**
 * Name intermediate results once and use them several times:
 * `let("hours" to field("hours"), "rate" to field("rate")) { it["hours"] * it["rate"] }`.
 */
fun let(vararg bindings: Pair<String, Any?>, build: (LetRefs) -> Any?): Expr {
    val refs = LetRefs(bindings.map { it.first }.toSet())
    val result = asExpr(build(refs))
    return node("let") {
        put("bindings", buildJsonObject { bindings.forEach { (k, v) -> put(k, asExpr(v).toJson()) } })
        put("result", result.toJson())
    }
}

// ----- queries -----

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

/** Sort keys shared by queries and includes. */
open class SortKeys internal constructor() {
    internal val sortKeys = mutableListOf<JsonObject>()

    fun sort(field: String, direction: SortDirection = SortDirection.ASCENDING) {
        sortKeys.add(buildJsonObject { put("field", field); put("direction", direction.wire) })
    }

    /** Sort by a computed value, e.g. `sort(lower(field("name")))`. */
    fun sort(expression: Expr, direction: SortDirection = SortDirection.ASCENDING) {
        sortKeys.add(buildJsonObject { put("expression", expression.toJson()); put("direction", direction.wire) })
    }
}

enum class Cardinality(val wire: String) { One("one"), Many("many") }

/**
 * A join: related rows from the same or another database ([db]), matched by
 * [localKey] (a parent field holding the child's docId or a list of them) or by
 * a [filter] with exactly one equality between a child term (`field(…)`, `docId()`)
 * and a parent term (`parent(…)`, `parentDocId()`), optionally ANDed with plain
 * child conditions.
 */
class IncludeBuilder internal constructor(private val cardinality: Cardinality) : SortKeys() {
    /** Database id of the related rows; the query's database when null. */
    var db: String? = null
    var localKey: String? = null
    var filter: Expr? = null
    /** The filter as formula source text, e.g. `v.eq(v.field("orderId"), v.parentDocId())`. */
    var filterFormula: String? = null
    var fields: List<String>? = null
    var limit: Int? = null
    private val includes = mutableMapOf<String, JsonObject>()

    fun include(name: String, cardinality: Cardinality, block: IncludeBuilder.() -> Unit) {
        includes[name] = IncludeBuilder(cardinality).apply(block).build()
    }

    internal fun build(): JsonObject = buildJsonObject {
        put("cardinality", cardinality.wire)
        db?.let { put("db", it) }
        localKey?.let { put("localKey", it) }
        (filter?.toJson() ?: filterFormula?.let { JsonPrimitive(it) })?.let { put("filter", it) }
        fields?.let { list -> put("fields", buildJsonArray { list.forEach { add(it) } }) }
        if (sortKeys.isNotEmpty()) put("sortBy", JsonArray(sortKeys))
        limit?.let { put("limit", it) }
        if (includes.isNotEmpty()) put("include", JsonObject(includes))
    }
}

/** Builder for a `MindooQuery` (ad-hoc summary query). */
class QueryBuilder : SortKeys() {
    var filter: Expr? = null
    /** The filter as formula source text (`v.eq(v.field("type"), "note")`); used when [filter] is null. */
    var filterFormula: String? = null
    var text: TextClause? = null
    var limit: Int? = null
    var offset: Int? = null
    var fields: List<String>? = null
    private val includes = mutableMapOf<String, JsonObject>()

    fun sortByTextScore(direction: SortDirection = SortDirection.DESCENDING) {
        sortKeys.add(buildJsonObject { put("special", "textScore"); put("direction", direction.wire) })
    }

    /** Add related rows under [name] to every result row (see [IncludeBuilder]). */
    fun include(name: String, cardinality: Cardinality, block: IncludeBuilder.() -> Unit) {
        includes[name] = IncludeBuilder(cardinality).apply(block).build()
    }

    fun build(): JsonObject = buildJsonObject {
        (filter?.toJson() ?: filterFormula?.let { JsonPrimitive(it) })?.let { put("filter", it) }
        text?.let { put("text", it.toJson()) }
        if (sortKeys.isNotEmpty()) put("sortBy", JsonArray(sortKeys))
        limit?.let { put("limit", it) }
        offset?.let { put("offset", it) }
        fields?.let { list -> put("fields", buildJsonArray { list.forEach { add(it) } }) }
        if (includes.isNotEmpty()) put("include", JsonObject(includes))
    }
}

fun mindooQuery(block: QueryBuilder.() -> Unit): JsonObject = QueryBuilder().apply(block).build()

// ----- views -----

enum class TotalMode(val wire: String) { None("none"), Sum("sum"), Average("average") }
enum class ColumnSorting(val wire: String) { None("none"), Ascending("ascending"), Descending("descending") }
enum class CategorizationStyle(val wire: String) {
    DocumentThenCategory("document_then_category"),
    CategoryThenDocument("category_then_document"),
}

/** Builder for a `MindooQueryViewDefinition` (ephemeral virtual view). */
class ViewDefinitionBuilder {
    var filter: Expr? = null
    var text: TextClause? = null
    var categorizationStyle: CategorizationStyle? = null
    private val columns = mutableListOf<JsonObject>()

    /**
     * A category column. The expression may return a list (one entry per value)
     * or a `\`-separated path (`pathJoin`) for sub-categories.
     */
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
    ) = addColumn(name, title, expression, sorting?.wire, totalMode, hidden)

    /** [column] with typed sorting and totals; `TotalMode.Sum`/`Average` show on category rows. */
    fun column(
        name: String,
        expression: Expr,
        title: String = name,
        sorting: ColumnSorting = ColumnSorting.None,
        totals: TotalMode = TotalMode.None,
        hidden: Boolean = false,
    ) = addColumn(
        name, title, expression,
        sorting.wire.takeIf { sorting != ColumnSorting.None },
        totals.wire.takeIf { totals != TotalMode.None },
        hidden,
    )

    private fun addColumn(name: String, title: String, expression: Expr, sorting: String?, totalMode: String?, hidden: Boolean) {
        columns.add(buildJsonObject {
            put("name", name)
            put("title", title)
            put("isCategory", false)
            put("isHidden", hidden)
            sorting?.let { put("sorting", it) }
            totalMode?.let { put("totalMode", it) }
            put("expression", expression.toJson())
        })
    }

    fun build(): JsonObject = buildJsonObject {
        filter?.let { put("filter", it.toJson()) }
        text?.let { put("text", it.toJson()) }
        categorizationStyle?.let { put("categorizationStyle", it.wire) }
        put("columns", JsonArray(columns))
    }
}

fun viewDefinition(block: ViewDefinitionBuilder.() -> Unit): JsonObject =
    ViewDefinitionBuilder().apply(block).build()

/**
 * One database feeding a multi-database view (`Database.openViewNavigator` with
 * sources). [filter] and [text] replace the definition's for this source;
 * [origin] names its entries (defaults to tenant/database).
 */
data class ViewSource(
    val db: String,
    val filter: Expr? = null,
    val text: TextClause? = null,
    val origin: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("db", db)
        filter?.let { put("filter", it.toJson()) }
        text?.let { put("text", it.toJson()) }
        origin?.let { put("origin", it) }
    }
}

/**
 * Options for `Database.openViewNavigator`: initial expansion ([expandAll] or
 * [expandLevel]) and which entries the navigator walks.
 */
fun navigatorOptions(
    expandAll: Boolean? = null,
    expandLevel: Int? = null,
    hideEmptyCategories: Boolean? = null,
    categoriesOnly: Boolean? = null,
    documentsOnly: Boolean? = null,
    fromCategory: String? = null,
): JsonObject = buildJsonObject {
    expandAll?.let { put("expandAll", it) }
    expandLevel?.let { put("expandLevel", it) }
    hideEmptyCategories?.let { put("hideEmptyCategories", it) }
    categoriesOnly?.let { put("categoriesOnly", it) }
    documentsOnly?.let { put("documentsOnly", it) }
    fromCategory?.let { put("fromCategory", it) }
}
