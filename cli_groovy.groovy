import groovy.transform.Immutable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

enum Published {
    YES, NO
}

enum SortStrategy {
    ID_ASC, ID_DESC, TITLE_ASC, TITLE_DESC, PRICE_ASC, PRICE_DESC
}

@Immutable
class Course {
    Byte id
    String title
    Published published
    Float price
}

@Immutable
class PriceRange {
    Float min
    Float max
}

@Immutable
class FilterCourses {
    List<Byte> ids = null
    String title = null
    Published published = null
    PriceRange priceRange = null
    SortStrategy sort = SortStrategy.ID_ASC

    static FilterCourses defaultFilter() {
        return new FilterCourses()
    }
}

@Immutable
class UpdateCourse {
    String title = null
    Published published = null
    Float price = null
}

class CourseRepository {

    private final AtomicReference<List<Course>> coursesRef

    CourseRepository(List<Course> initialData) {
        this.coursesRef = new AtomicReference<>(initialData.asUnmodifiable())
    }

    List<Course> fetchCourses(FilterCourses filtered) {
        List<Course> currentSnapshot = coursesRef.get()

        List<Course> results = currentSnapshot.findAll { course ->
            if (filtered.ids != null && !filtered.ids.contains(course.id)) return false
            if (filtered.published != null && course.published != filtered.published) return false
            if (filtered.title != null && !course.title.toLowerCase().contains(filtered.title.toLowerCase())) return false
            if (filtered.priceRange != null) {
                if (filtered.priceRange.min != null && course.price < filtered.priceRange.min) return false
                if (filtered.priceRange.max != null && course.price > filtered.priceRange.max) return false
            }
            return true
        }

        SortStrategy strategy = filtered.sort ?: SortStrategy.ID_ASC
        results.sort(false) { Course a, Course b ->
            switch (strategy) {
                case SortStrategy.ID_ASC:     return a.id <=> b.id
                case SortStrategy.ID_DESC:    return b.id <=> a.id
                case SortStrategy.TITLE_ASC:  return a.title <=> b.title
                case SortStrategy.TITLE_DESC: return b.title <=> a.title
                case SortStrategy.PRICE_ASC:  return a.price <=> b.price
                case SortStrategy.PRICE_DESC: return b.price <=> a.price
            }
        }

        return results
    }

    void updateCourse(Byte id, UpdateCourse updates) {
        List<Course> currentSnapshot
        List<Course> proposedState
        boolean found

        do {
            currentSnapshot = coursesRef.get()
            proposedState = currentSnapshot.collect { Course c ->
                if (c.id == id) {
                    found = true
                    return new Course(
                        c.id,
                        updates.title ?: c.title,
                        updates.published ?: c.published,
                        updates.price != null ? (updates.price as Float) : c.price
                    )
                }
                return c
            }

            if (!found) {
                throw new NoSuchElementException("Not Found Error: Course with ID $id does not exist!")
            }

        } while (!coursesRef.compareAndSet(currentSnapshot, proposedState.asUnmodifiable()))
    }

    void addCourse(Course course) {
        List<Course> currentSnapshot
        List<Course> proposedState

        do {
            currentSnapshot = coursesRef.get()
            if (currentSnapshot.any { it.id == course.id }) {
                throw new IllegalArgumentException("Validation Error: Course with ID ${course.id} already exists!")
            }
            proposedState = currentSnapshot + course
        } while (!coursesRef.compareAndSet(currentSnapshot, proposedState.asUnmodifiable()))
    }

    void deleteCourse(Byte id) {
        List<Course> currentSnapshot
        List<Course> proposedState

        do {
            currentSnapshot = coursesRef.get()
            proposedState = currentSnapshot.findAll { it.id != id }
            if (proposedState.size() == currentSnapshot.size()) {
                throw new NoSuchElementException("Not Found Error: Course with ID $id does not exist!")
            }
        } while (!coursesRef.compareAndSet(currentSnapshot, proposedState.asUnmodifiable()))
    }
}

static void main(String[] args) {
    List<Course> initialCourses = [
        new Course(2 as Byte, "Title 2", Published.NO, 10.0f as Float),
        new Course(3 as Byte, "Product 3", Published.YES, 45.5f as Float),
        new Course(1 as Byte, "Title 1", Published.YES, 25.0f as Float),
        new Course(5 as Byte, "Product 5", Published.NO, 120.0f as Float),
        new Course(6 as Byte, "Product 6", Published.YES, 75.0f as Float),
        new Course(4 as Byte, "Title 4", Published.YES, 9.99f as Float)
    ]

    CourseRepository repo = new CourseRepository(initialCourses)

    Executors.newVirtualThreadPerTaskExecutor().withCloseable { executor ->
        println("\n--- Adding Course ---")
        CompletableFuture<Void> addJob = CompletableFuture.runAsync({
            println("Adding course with (ID : 7, Title 7, Published : YES, Price: 50.0) inside background thread...")
            try {
                repo.addCourse(new Course(7 as Byte, "Title 7", Published.YES, 50.0f as Float))
            } catch (Exception e) {
                println("Error adding course in background thread: ${e.message}")
            }
        }, executor)
        addJob.join()

        println("\n--- Updating Course ---")
        UpdateCourse updates = new UpdateCourse(title: "Product 1", published: Published.NO, price: 29.99f)
        CompletableFuture<Void> updateJob = CompletableFuture.runAsync({
            println("Updating Title, Published status, and Price for Course with ID 1 inside background thread...")
            try {
                repo.updateCourse(1 as Byte, updates)
            } catch (Exception e) {
                println("Error updating course in background thread: ${e.message}")
            }
        }, executor)
        updateJob.join()
    }

    println("\n--- Deleting Course (with ID 3) ---")
    try {
        repo.deleteCourse(3 as Byte)
        println("Successfully deleted course with ID 3")
    } catch (Exception e) {
        println("Error deleting course: ${e.message}")
    }

    println("\n--- Fetching Courses ---")
    repo.fetchCourses(FilterCourses.defaultFilter()).each { println(it) }
}