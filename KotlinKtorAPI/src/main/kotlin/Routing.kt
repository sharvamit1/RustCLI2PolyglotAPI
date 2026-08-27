package api.example

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.NoSuchElementException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable

@Serializable
enum class Published { YES, NO }

@Serializable
enum class SortStrategy { ID_ASC, ID_DESC, TITLE_ASC, TITLE_DESC, PRICE_ASC, PRICE_DESC }

@Serializable
data class Course(
    val id: Byte,
    val title: String,
    val published: Published,
    val price: Float
)

@Serializable
data class PriceRange(val min: Float?, val max: Float?)

@Serializable
data class FilterCourses(
    val ids: List<Byte>? = null,
    val title: String? = null,
    val published: Published? = null,
    val priceRange: PriceRange? = null,
    val sort: SortStrategy = SortStrategy.ID_ASC
) {
    companion object {
        fun defaultFilter() = FilterCourses()
    }
}

@Serializable
data class UpdateCourse(
    val title: String? = null,
    val published: Published? = null,
    val price: Float? = null
)

class CourseRepository(initialData: List<Course>) {
    private val coursesRef = AtomicReference(initialData.toList())

    fun fetchCourses(filtered: FilterCourses): Flow<Course> = flow {
        val currentSnapshot = coursesRef.get()
        val results = currentSnapshot.filter { course ->
            if (filtered.ids != null && !filtered.ids.contains(course.id)) return@filter false
            if (filtered.published != null && course.published != filtered.published) return@filter false
            if (filtered.title != null && !course.title.contains(filtered.title, ignoreCase = true)) return@filter false
            if (filtered.priceRange != null) {
                if (filtered.priceRange.min != null && course.price < filtered.priceRange.min) return@filter false
                if (filtered.priceRange.max != null && course.price > filtered.priceRange.max) return@filter false
            }
            true
        }.toMutableList()

        when (filtered.sort) {
            SortStrategy.ID_ASC -> results.sortBy { it.id }
            SortStrategy.ID_DESC -> results.sortByDescending { it.id }
            SortStrategy.TITLE_ASC -> results.sortBy { it.title }
            SortStrategy.TITLE_DESC -> results.sortByDescending { it.title }
            SortStrategy.PRICE_ASC -> results.sortBy { it.price }
            SortStrategy.PRICE_DESC -> results.sortByDescending { it.price }
        }

        for (course in results) {
            emit(course)
        }
    }.flowOn(Dispatchers.Default)

    fun updateCourse(id: Byte, updates: UpdateCourse) {
        var currentSnapshot: List<Course>
        var proposedState: List<Course>
        do {
            currentSnapshot = coursesRef.get()
            val index = currentSnapshot.indexOfFirst { it.id == id }
            if (index == -1) {
                throw NoSuchElementException("Not Found Error: Course with ID $id does not exist!")
            }
            val current = currentSnapshot[index]
            val updated = current.copy(
                title = updates.title ?: current.title,
                published = updates.published ?: current.published,
                price = updates.price ?: current.price
            )
            proposedState = currentSnapshot.toMutableList().apply {
                set(index, updated)
            }
        } while (!coursesRef.compareAndSet(currentSnapshot, proposedState.toList()))
    }

    fun addCourse(course: Course) {
        var currentSnapshot: List<Course>
        var proposedState: List<Course>
        do {
            currentSnapshot = coursesRef.get()
            if (currentSnapshot.any { it.id == course.id }) {
                throw IllegalArgumentException("Validation Error: Course with ID ${course.id} already exists!")
            }
            proposedState = currentSnapshot + course
        } while (!coursesRef.compareAndSet(currentSnapshot, proposedState.toList()))
    }

    fun deleteCourse(id: Byte) {
        var currentSnapshot: List<Course>
        var proposedState: List<Course>
        do {
            currentSnapshot = coursesRef.get()
            proposedState = currentSnapshot.filterNot { it.id == id }
            if (proposedState.size == currentSnapshot.size) {
                throw NoSuchElementException("Not Found Error: Course with ID $id does not exist!")
            }
        } while (!coursesRef.compareAndSet(currentSnapshot, proposedState.toList()))
    }
}

val initialCourses = listOf(
    Course(2, "Title 2", Published.NO, 10.0f),
    Course(3, "Product 3", Published.YES, 45.5f),
    Course(1, "Title 1", Published.YES, 25.0f),
    Course(5, "Product 5", Published.NO, 120.0f),
    Course(6, "Product 6", Published.YES, 75.0f),
    Course(4, "Title 4", Published.YES, 9.99f)
)
val repo = CourseRepository(initialCourses)

fun Application.configureRouting() {
    if (pluginOrNull(ContentNegotiation) == null) {
        install(ContentNegotiation) {
            json()
        }
    }

    routing {
        route("api/v1") {
            get("/courses") { handleGetCourses(call) }
            post("/courses") { handlePostCourse(call) }
            put("/courses/{id}") { handlePutCourse(call) }
            delete("/courses/{id}") { handleDeleteCourse(call) }
        }
    }
}

suspend fun handleGetCourses(call: ApplicationCall) {
    val queryParams = call.request.queryParameters
    val ids = queryParams["ids"]?.split(",")?.mapNotNull { it.trim().toByteOrNull() }
    val title = queryParams["title"]
    val published = queryParams["published"]?.let {
        runCatching { Published.valueOf(it.uppercase()) }.getOrNull()
    }
    val minPrice = queryParams["minPrice"]?.toFloatOrNull()
    val maxPrice = queryParams["maxPrice"]?.toFloatOrNull()
    val sort = queryParams["sort"]?.let {
        runCatching { SortStrategy.valueOf(it.uppercase()) }.getOrDefault(SortStrategy.ID_ASC)
    } ?: SortStrategy.ID_ASC

    val filter = FilterCourses(
        ids = ids,
        title = title,
        published = published,
        priceRange = PriceRange(minPrice, maxPrice),
        sort = sort
    )

    val matchingCourses = repo.fetchCourses(filter).toList()
    call.respond(HttpStatusCode.OK, matchingCourses)
}

suspend fun handlePostCourse(call: ApplicationCall) {
    runCatching { call.receive<Course>() }
        .onSuccess { newCourse ->
            try {
                repo.addCourse(newCourse)
                call.respond(HttpStatusCode.Created, mapOf("message" to "Course successfully added!"))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Validation Failure")))
            }
        }
        .onFailure {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Malformed JSON schema payload"))
        }
}

suspend fun handlePutCourse(call: ApplicationCall) {
    val idParam = call.parameters["id"]?.toByteOrNull()
    if (idParam == null) {
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing or structurally malformed Byte ID"))
        return
    }

    runCatching { call.receive<UpdateCourse>() }
        .onSuccess { updatePayload ->
            try {
                repo.updateCourse(idParam, updatePayload)
                call.respond(HttpStatusCode.OK, mapOf("message" to "Course with ID $idParam was updated!"))
            } catch (e: NoSuchElementException) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to (e.message ?: "Course context missing")))
            }
        }
        .onFailure {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Malformed update payload layout"))
        }
}

suspend fun handleDeleteCourse(call: ApplicationCall) {
    val idParam = call.parameters["id"]?.toByteOrNull()
    if (idParam == null) {
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing or structurally malformed Byte ID"))
        return
    }

    try {
        repo.deleteCourse(idParam)
        call.respond(HttpStatusCode.OK, mapOf("message" to "Course with ID $idParam successfully deleted"))
    } catch (e: NoSuchElementException) {
        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Resource not found"))
    }
}