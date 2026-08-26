import java.util.NoSuchElementException
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*

enum Published:
  case YES, NO

enum SortStrategy:
  case ID_ASC, ID_DESC, TITLE_ASC, TITLE_DESC, PRICE_ASC, PRICE_DESC

case class Course(
  id: Byte,
  title: String,
  published: Published,
  price: Float
)

case class PriceRange(min: Option[Float] = None, max: Option[Float] = None)

case class FilterCourses(
  ids: Option[List[Byte]] = None,
  title: Option[String] = None,
  published: Option[Published] = None,
  priceRange: Option[PriceRange] = None,
  sort: SortStrategy = SortStrategy.ID_ASC
)

object FilterCourses:
  def defaultFilter(): FilterCourses = FilterCourses()

case class UpdateCourse(
  title: Option[String] = None,
  published: Option[Published] = None,
  price: Option[Float] = None
)

class CourseRepository(initialData: List[Course]):

  private val coursesRef = new AtomicReference[List[Course]](initialData)

  def fetchCourses(filtered: FilterCourses)(using ec: ExecutionContext): Future[List[Course]] = Future {
    val currentSnapshot = coursesRef.get()

    var results = currentSnapshot.filter(course =>
      val matchesIds = filtered.ids.forall(_.contains(course.id))
      val matchesPublished = filtered.published.forall(_ == course.published)
      val matchesTitle = filtered.title.forall(t => course.title.toLowerCase.contains(t.toLowerCase))
      val matchesPrice = filtered.priceRange.forall { range =>
        range.min.forall(course.price >= _) && range.max.forall(course.price <= _)
      }
      matchesIds && matchesPublished && matchesTitle && matchesPrice
    )

    results = filtered.sort match
      case SortStrategy.ID_ASC     => results.sortBy(_.id)
      case SortStrategy.ID_DESC    => results.sortBy(_.id)(Ordering[Byte].reverse)
      case SortStrategy.TITLE_ASC  => results.sortBy(_.title)
      case SortStrategy.TITLE_DESC => results.sortBy(_.title)(Ordering[String].reverse)
      case SortStrategy.PRICE_ASC  => results.sortBy(_.price)
      case SortStrategy.PRICE_DESC => results.sortBy(_.price)(Ordering[Float].reverse)

    results
  }

  def updateCourse(id: Byte, updates: UpdateCourse)(using ec: ExecutionContext): Future[Unit] = Future {
    var currentSnapshot = List.empty[Course]
    var proposedState = List.empty[Course]
    var success = false

    while (!success) do
      currentSnapshot = coursesRef.get()
      val index = currentSnapshot.indexWhere(_.id == id)
      if index == -1 then
        throw new NoSuchElementException(s"Not Found Error: Course with ID $id does not exist!")

      val current = currentSnapshot(index)
      val updated = current.copy(
        title = updates.title.getOrElse(current.title),
        published = updates.published.getOrElse(current.published),
        price = updates.price.getOrElse(current.price)
      )

      proposedState = currentSnapshot.updated(index, updated)
      success = coursesRef.compareAndSet(currentSnapshot, proposedState)
  }

  def addCourse(course: Course)(using ec: ExecutionContext): Future[Unit] = Future {
    var currentSnapshot = List.empty[Course]
    var proposedState = List.empty[Course]
    var success = false

    while (!success) do
      currentSnapshot = coursesRef.get()
      if currentSnapshot.exists(_.id == course.id) then
        throw new IllegalArgumentException(s"Validation Error: Course with ID ${course.id} already exists!")
      
      proposedState = currentSnapshot :+ course
      success = coursesRef.compareAndSet(currentSnapshot, proposedState)
  }

  def deleteCourse(id: Byte)(using ec: ExecutionContext): Future[Unit] = Future {
    var currentSnapshot = List.empty[Course]
    var proposedState = List.empty[Course]
    var success = false

    while (!success) do
      currentSnapshot = coursesRef.get()
      proposedState = currentSnapshot.filterNot(_.id == id)
      if proposedState.size == currentSnapshot.size then
        throw new NoSuchElementException(s"Not Found Error: Course with ID $id does not exist!")
      
      success = coursesRef.compareAndSet(currentSnapshot, proposedState)
  }

@main def run(): Unit =
  given ec: ExecutionContext = scala.concurrent.ExecutionContext.global

  val initialCourses = List(
    Course(2.toByte, "Title 2", Published.NO, 10.0f),
    Course(3.toByte, "Product 3", Published.YES, 45.5f),
    Course(1.toByte, "Title 1", Published.YES, 25.0f),
    Course(5.toByte, "Product 5", Published.NO, 120.0f),
    Course(6.toByte, "Product 6", Published.YES, 75.0f),
    Course(4.toByte, "Title 4", Published.YES, 9.99f)
  )

  val repo = CourseRepository(initialCourses)

  println("\n--- Adding Course ---")
  println("Adding course with (ID : 7, Title 7, Published : YES, Price: 50.0) inside background thread...")
  val addJob = repo.addCourse(Course(7.toByte, "Title 7", Published.YES, 50.0f)).recover {
    case e: Exception => println(s"Error adding course in background thread: ${e.getMessage}")
  }
  Await.result(addJob, 5.seconds)

  println("\n--- Updating Course ---")
  val updates = UpdateCourse(title = Some("Product 1"), published = Some(Published.NO), price = Some(29.99f))
  println("Updating Title, Published status, and Price for Course with ID 1 inside background thread...")
  val updateJob = repo.updateCourse(1.toByte, updates).recover {
    case e: Exception => println(s"Error updating course in background thread: ${e.getMessage}")
  }
  Await.result(updateJob, 5.seconds)

  println("\n--- Deleting Course (with ID 3) ---")
  val deleteJob = repo.deleteCourse(3.toByte).map { _ =>
    println("Successfully deleted course with ID 3")
  }.recover {
    case e: Exception => println(s"Error deleting course: ${e.getMessage}")
  }
  Await.result(deleteJob, 5.seconds)

  println("\n--- Fetching Courses ---")
  val finalStateJob = repo.fetchCourses(FilterCourses.defaultFilter()).map { courses =>
    courses.foreach(println)
  }
  Await.result(finalStateJob, 5.seconds)