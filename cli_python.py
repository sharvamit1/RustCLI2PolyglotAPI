import asyncio
from dataclasses import dataclass, field
from enum import Enum, auto


class Published(Enum):
    YES = auto()
    NO = auto()

    def __str__(self) -> str:
        return self.name.capitalize()

    def __repr__(self) -> str:
        return self.name.capitalize()


class SortStrategy(Enum):
    ID_ASC = auto()
    ID_DESC = auto()
    TITLE_ASC = auto()
    TITLE_DESC = auto()
    PRICE_ASC = auto()
    PRICE_DESC = auto()


@dataclass
class Course:
    id: int
    title: str
    published: Published
    price: float


@dataclass
class PriceRange:
    min: float | None = None
    max: float | None = None


@dataclass
class FilterCourses:
    ids: list[int] | None = None
    title: str | None = None
    published: Published | None = None
    price_range: PriceRange | None = None
    sort: SortStrategy | None = SortStrategy.ID_ASC


@dataclass
class UpdateCourse:
    title: str | None = None
    published: Published | None = None
    price: float | None = None


class CourseRepository:
    def __init__(self, initial_data: list[Course] | None = None) -> None:
        self._courses: list[Course] = (
            [Course(**c.__dict__) for c in initial_data] if initial_data else []
        )
        self._lock = asyncio.Lock()

    async def fetch_courses(
        self, filtered: FilterCourses | None = None
    ) -> list[Course]:
        if filtered is None:
            filtered = FilterCourses()

        async with self._lock:
            results = [Course(**c.__dict__) for c in self._courses]

        filtered_results: list[Course] = []
        for course in results:
            if filtered.ids is not None and course.id not in filtered.ids:
                continue
            if (
                filtered.published is not None
                and course.published != filtered.published
            ):
                continue
            if (
                filtered.title is not None
                and filtered.title.lower() not in course.title.lower()
            ):
                continue
            if filtered.price_range is not None:
                if (
                    filtered.price_range.min is not None
                    and course.price < filtered.price_range.min
                ):
                    continue
                if (
                    filtered.price_range.max is not None
                    and course.price > filtered.price_range.max
                ):
                    continue
            filtered_results.append(course)

        strategy = filtered.sort or SortStrategy.ID_ASC
        match strategy:
            case SortStrategy.ID_ASC:
                filtered_results.sort(key=lambda x: x.id)
            case SortStrategy.ID_DESC:
                filtered_results.sort(key=lambda x: x.id, reverse=True)
            case SortStrategy.TITLE_ASC:
                filtered_results.sort(key=lambda x: x.title)
            case SortStrategy.TITLE_DESC:
                filtered_results.sort(key=lambda x: x.title, reverse=True)
            case SortStrategy.PRICE_ASC:
                filtered_results.sort(key=lambda x: x.price)
            case SortStrategy.PRICE_DESC:
                filtered_results.sort(key=lambda x: x.price, reverse=True)

        return filtered_results

    async def add_course(self, course: Course) -> None:
        async with self._lock:
            if any(c.id == course.id for c in self._courses):
                raise ValueError(
                    f"Validation Error: Course with ID {course.id} already exists!"
                )
            self._courses.append(Course(**course.__dict__))

    async def update_course(self, id: int, updates: UpdateCourse) -> None:
        async with self._lock:
            for course in self._courses:
                if course.id == id:
                    if updates.title is not None:
                        course.title = updates.title
                    if updates.published is not None:
                        course.published = updates.published
                    if updates.price is not None:
                        course.price = updates.price
                    return
            raise LookupError(
                f"Not Found Error: Course with ID {id} does not exist!"
            )

    async def delete_course(self, id: int) -> None:
        async with self._lock:
            initial_len = len(self._courses)
            self._courses = [c for c in self._courses if c.id != id]
            if len(self._courses) == initial_len:
                raise LookupError(
                    f"Not Found Error: Course with ID {id} does not exist!"
                )


async def main() -> None:
    initial_courses = [
        Course(id=2, title="Title 2", published=Published.NO, price=10.0),
        Course(id=3, title="Product 3", published=Published.YES, price=45.5),
        Course(id=1, title="Title 1", published=Published.YES, price=25.0),
        Course(id=5, title="Product 5", published=Published.NO, price=120.0),
        Course(id=6, title="Product 6", published=Published.YES, price=75.0),
        Course(id=4, title="Title 4", published=Published.YES, price=9.99),
    ]

    repo = CourseRepository(initial_courses)

    print("\n--- Adding Course ---")

    async def add_job():
        print(
            "Adding course with (ID : 7, Title 7, Published : YES, Price: 50.0) inside background task..."
        )
        try:
            await repo.add_course(
                Course(
                    id=7,
                    title="Title 7",
                    published=Published.YES,
                    price=50.0,
                )
            )
        except Exception as e:
            print(f"Error adding course in background task: {e}")

    await asyncio.create_task(add_job())

    print("\n--- Updating Course ---")

    async def update_job():
        print(
            "Updating Title, Published status, and Price for Course with ID 1 inside background task..."
        )
        updates = UpdateCourse(
            title="Product 1", published=Published.NO, price=29.99
        )
        try:
            await repo.update_course(1, updates)
        except Exception as e:
            print(f"Error updating course in background task: {e}")

    await asyncio.create_task(update_job())

    print("\n--- Deleting Course (with ID 3) ---")
    try:
        await repo.delete_course(3)
        print("Successfully deleted course with ID 3")
    except Exception as e:
        print(f"Error deleting course: {e}")

    print("\n--- Fetching Courses ---")
    final_courses = await repo.fetch_courses()
    for c in final_courses:
        print(c)


if __name__ == "__main__":
    asyncio.run(main())