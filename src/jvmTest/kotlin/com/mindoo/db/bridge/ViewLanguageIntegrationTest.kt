package com.mindoo.db.bridge

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Kotlin view-language DSL against the real engine: every operation family, joins, multi-database views. */
class ViewLanguageIntegrationTest {

    private lateinit var dataRoot: Path
    private var sidecar: SidecarProcess? = null

    private val bridgeDir: Path = Paths.get("..", "mindoodb-jvm-bridge").toAbsolutePath().normalize()
    private val indexScript: Path get() = bridgeDir.resolve("dist/index.js")

    @BeforeTest
    fun setup() {
        if (!indexScript.exists()) return
        dataRoot = Files.createTempDirectory("mindoodb-bridge-vl-it")
    }

    @AfterTest
    fun tearDown() {
        sidecar?.stop()
        if (::dataRoot.isInitialized) dataRoot.toFile().deleteRecursively()
    }

    private fun QueryResult.strings(field: String) = rows.map { it.fields[field]?.jsonPrimitive?.contentOrNull }

    @Test
    fun expressionsJoinsAndMultiDatabaseViews() {
        if (!indexScript.exists()) {
            println("[integration] skipped: build the sidecar (dist/)")
            return
        }
        runBlocking {
            sidecar = Sidecar.start(indexScript, dataRoot)
            val endpoint = sidecar!!.endpoint
            val created = MindooDBBridge.createTenant(
                endpoint, tenantId = null, adminName = "cn=admin", adminPassword = "adminpw",
                userId = "cn=alice", password = "pwA", databases = listOf("work", "crm"),
            )
            val session = MindooDBBridge.openSession(endpoint, created.tenantId, "cn=alice", "pwA", listOf("work", "crm"))
            try {
                val work = session.database("work")
                val crm = session.database("crm")
                val acme = crm.documents.create(mapOf("type" to "customer", "name" to "Acme", "country" to "DE"), idPrefix = "cus")
                val globex = crm.documents.create(mapOf("type" to "customer", "name" to "Globex", "country" to "US"), idPrefix = "cus")
                work.documents.createMany(listOf(
                    mapOf("type" to "time", "employee" to "Ada", "hours" to 8, "rate" to 10, "day" to "2026-04-01", "customer" to acme.id, "meta" to "{\"tag\":\"x\"}"),
                    mapOf("type" to "time", "employee" to "Ada", "hours" to 4, "rate" to 11, "day" to "2026-07-02", "customer" to globex.id),
                    mapOf("type" to "time", "employee" to "bob", "hours" to 0, "rate" to 12, "day" to "2025-12-30", "customer" to acme.id, "note" to "holiday"),
                ))
                session.awaitIndexReady()
                suspend fun q(block: QueryBuilder.() -> Unit) = work.query(mindooQuery(block))

                // arithmetic + comparison: amount = hours * rate > 40
                assertEquals(setOf(80.0, 44.0), q { filter = (field("type") eq "time") and (field("hours") * field("rate") gt 40) }
                    .rows.map { (it.fields["hours"]!!.jsonPrimitive.double * it.fields["rate"]!!.jsonPrimitive.double) }.toSet())
                // let + ifElse + coalesce
                val label = let("h" to field("hours")) { ifElse(it["h"] gte 8, "full", it["h"] gt 0, "part", "off") }
                assertEquals(listOf("bob"), q { filter = (field("type") eq "time") and (label eq "off") }.strings("employee"))
                assertEquals(1, q { filter = coalesce(field("note"), "none") eq "holiday" }.rows.size)
                // text helpers: case-insensitive contains, lower, concat, left
                assertEquals(1, q { filter = contains(field("employee"), "BO") }.rows.size)
                assertEquals(2, q { filter = lower(field("employee")) eq "ada" }.rows.size)
                assertEquals(1, q { filter = concat(field("employee"), "-", field("hours")) eq "Ada-4" }.rows.size)
                assertEquals(2, q { filter = left(field("day"), 4) eq "2026" }.rows.size)
                // dates and conversions
                assertEquals(1, q { filter = datePart(field("day"), DatePart.Quarter) eq "Q3" }.rows.size)
                assertEquals(2, q { filter = datePart(field("day"), DatePart.Year) eq 2026 }.rows.size)
                assertEquals(1, q { filter = asNumber(field("hours")) eq 4 }.rows.size)
                // existence, metadata, json
                assertEquals(1, q { filter = exists("note") }.rows.size)
                assertEquals(2, q { filter = (field("type") eq "time") and notExists(field("note")) }.rows.size)
                assertEquals(3, q { filter = (field("type") eq "time") and not(isAwaitingWitness() eq "x") }.rows.size)
                assertEquals(3, q { filter = attachmentCount() eq 0 }.rows.size)
                assertEquals(1, q { filter = json("meta", "tag") eq "x" }.rows.size)
                assertEquals(2, crm.query(mindooQuery { filter = startsWith(docId(), "cus_") }).rows.size)
                // formula source text and sorting by expression
                assertEquals(1, q { filterFormula = "v.eq(v.field(\"employee\"), \"bob\")" }.rows.size)
                assertEquals(
                    listOf("Ada", "Ada", "bob"),
                    q { filter = field("type") eq "time"; sort(lower(field("employee"))); sort("hours", SortDirection.DESCENDING) }.strings("employee"),
                )

                // join: each time entry with its customer from the crm database
                val joined = q {
                    filter = field("type") eq "time"
                    sort("day")
                    include("customer", Cardinality.One) {
                        db = "crm"
                        localKey = "customer"
                    }
                }
                assertEquals(
                    listOf("Acme", "Acme", "Globex"),
                    joined.rows.map { it.included("customer")?.fields?.get("name")?.jsonPrimitive?.content },
                    "join result: ${joined.rows}",
                )
                // many: each customer with its time entries (filter form with parent())
                val perCustomer = crm.query(mindooQuery {
                    filter = field("type") eq "customer"
                    sort("name")
                    include("entries", Cardinality.Many) {
                        db = "work"
                        filter = field("customer") eq parentDocId()
                        sort("hours", SortDirection.DESCENDING)
                    }
                })
                assertEquals(listOf(2, 1), perCustomer.rows.map { it.includedList("entries").size })


                // view: category path, typed totals, value() of an earlier column
                val nav = work.openViewNavigator(
                    viewDefinition {
                        filter = field("type") eq "time"
                        categorizationStyle = CategorizationStyle.CategoryThenDocument
                        category("bucket", expression = pathJoin(left(field("day"), 4), field("employee")))
                        column("amount", field("hours") * field("rate"), totals = TotalMode.Sum, sorting = ColumnSorting.Descending)
                        column("double", value("amount") * 2)
                    },
                    navigatorOptions = navigatorOptions(expandAll = true),
                )
                val entries = nav.readForward()
                val year2026 = entries.first { it.isCategory && it.level == 0 && it.categoryValue?.jsonPrimitive?.content == "2026" }
                assertEquals(124.0, year2026.columnValues["amount"]!!.jsonPrimitive.double, "category total: $entries")
                // 2025\bob comes first; Ada's entries sit under 2026\Ada, sorted by amount descending
                val ada = entries.dropWhile { it != year2026 }.filter { it.isDocument && it.level == 2 }
                assertEquals(listOf(80.0, 44.0), ada.take(2).map { it.columnValues["amount"]!!.jsonPrimitive.double })
                assertEquals(160.0, ada.first().columnValues["double"]!!.jsonPrimitive.double)
                nav.close()

                // multi-database view: origin() tells the sources apart
                val multi = work.openViewNavigator(
                    viewDefinition {
                        category("source", expression = origin())
                        column("label", coalesce(field("name"), field("employee")))
                    },
                    navigatorOptions = navigatorOptions(expandAll = true),
                    sources = listOf(
                        ViewSource("work", filter = field("type") eq "time", origin = "work"),
                        ViewSource("crm", filter = field("type") eq "customer", origin = "crm"),
                    ),
                )
                val all = multi.readForward()
                assertEquals(listOf("crm", "work"), all.filter { it.isCategory }.map { it.categoryValue!!.jsonPrimitive.content })
                assertEquals(5, all.count { it.isDocument })
                assertTrue(all.any { it.columnValues["label"]?.jsonPrimitive?.content == "Globex" })
                multi.close()
            } finally {
                session.close()
            }
        }
    }
}
