import java.util.NoSuchElementException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class Published {
    YES, NO
}

enum class SortStrategy {
    ID_ASC, ID_DESC, TITLE_ASC, TITLE_DESC, PRICE_ASC, PRICE_DESC
}

data class Course(
    val id: Byte,
    val title: String,
    val published: Published,
    val price: Float
)

data class PriceRange(val min: Float?, val max: Float?)

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

            proposedState = currentSnapshot.toMutableList().apply { set(index, updated) }
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

fun main() = runBlocking {
    val initialCourses = listOf(
        Course(2, "Title 2", Published.NO, 10.0f),
        Course(3, "Product 3", Published.YES, 45.5f),
        Course(1, "Title 1", Published.YES, 25.0f),
        Course(5, "Product 5", Published.NO, 120.0f),
        Course(6, "Product 6", Published.YES, 75.0f),
        Course(4, "Title 4", Published.YES, 9.99f)
    )

    val repo = CourseRepository(initialCourses)

    coroutineScope {
        println("\n--- Adding Course ---")
        val addJob = launch(Dispatchers.Default) {
            println("Adding course with (ID : 7, Title 7, Published : YES, Price: 50.0) inside background worker coroutine...")
            try {
                repo.addCourse(Course(7, "Title 7", Published.YES, 50.0f))
            } catch (e: Exception) {
                println("Error adding course in coroutine context: ${e.message}")
            }
        }
        addJob.join()

        println("\n--- Updating Course ---")
        val updates = UpdateCourse(title = "Product 1", published = Published.NO, price = 29.99f)
        val updateJob = launch(Dispatchers.Default) {
            println("Updating Title, Published status, and Price for Course with ID 1 inside background worker coroutine...")
            try {
                repo.updateCourse(1, updates)
            } catch (e: Exception) {
                println("Error updating course in coroutine context: ${e.message}")
            }
        }
        updateJob.join()
    }

    println("\n--- Deleting Course (with ID 3) ---")
    try {
        repo.deleteCourse(3)
        println("Successfully deleted course with ID 3")
    } catch (e: Exception) {
        println("Error deleting course: ${e.message}")
    }
	
   println("\n--- Fetching Courses (Unfiltered via Flow API) ---")
    repo.fetchCourses(FilterCourses.defaultFilter())
        .collect { course -> 
            println(course) 
        }
}