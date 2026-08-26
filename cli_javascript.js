export const Published = Object.freeze({
  Yes: "Yes",
  No: "No",
});

export const SortStrategy = Object.freeze({
  IdAsc: "IdAsc",
  IdDesc: "IdDesc",
  TitleAsc: "TitleAsc",
  TitleDesc: "TitleDesc",
  PriceAsc: "PriceAsc",
  PriceDesc: "PriceDesc",
});

export class CourseRepository {
  #courses;

  constructor(initialData = []) {
    this.#courses = initialData.map((course) => ({ ...course }));
  }

  async fetchCourses(filter = {}) {
    const { 
      ids, 
      title, 
      published, 
      priceRange, 
      sort = SortStrategy.IdAsc 
    } = filter;

    let results = this.#courses.filter((course) => {
      if (ids && !ids.includes(course.id)) return false;
      if (published && course.published !== published) return false;
      if (title && !course.title.toLowerCase().includes(title.toLowerCase())) return false;
      
      if (priceRange) {
        if (priceRange.min !== undefined && course.price < priceRange.min) return false;
        if (priceRange.max !== undefined && course.price > priceRange.max) return false;
      }
      return true;
    });

    results = results.map((course) => ({ ...course }));

    switch (sort) {
      case SortStrategy.IdAsc:
        return results.sort((a, b) => a.id - b.id);
      case SortStrategy.IdDesc:
        return results.sort((a, b) => b.id - a.id);
      case SortStrategy.TitleAsc:
        return results.sort((a, b) => a.title.localeCompare(b.title));
      case SortStrategy.TitleDesc:
        return results.sort((a, b) => b.title.localeCompare(a.title));
      case SortStrategy.PriceAsc:
        return results.sort((a, b) => a.price - b.price);
      case SortStrategy.PriceDesc:
        return results.sort((a, b) => b.price - a.price);
      default:
        return results;
    }
  }

  async addCourse(course) {
    const exists = this.#courses.some((c) => c.id === course.id);
    if (exists) {
      throw new Error(`Validation Error: Course with ID ${course.id} already exists!`);
    }
    this.#courses.push({ ...course });
  }

  async updateCourse(id, updates) {
    const target = this.#courses.find((c) => c.id === id);
    if (!target) {
      throw new Error(`Not Found Error: Course with ID ${id} does not exist!`);
    }

    if (updates.title !== undefined) target.title = updates.title;
    if (updates.published !== undefined) target.published = updates.published;
    if (updates.price !== undefined) target.price = updates.price;
  }

  async deleteCourse(id) {
    const index = this.#courses.findIndex((c) => c.id === id);
    if (index === -1) {
      throw new Error(`Not Found Error: Course with ID ${id} does not exist!`);
    }
    this.#courses.splice(index, 1);
  }
}

async function main() {
  const initialCourses = [
    { id: 2, title: "Title 2", published: Published.No, price: 10.0 },
    { id: 3, title: "Product 3", published: Published.Yes, price: 45.5 },
    { id: 1, title: "Title 1", published: Published.Yes, price: 25.0 },
    { id: 5, title: "Product 5", published: Published.No, price: 120.0 },
    { id: 6, title: "Product 6", published: Published.Yes, price: 75.0 },
    { id: 4, title: "Title 4", published: Published.Yes, price: 9.99 },
  ];

  const repo = new CourseRepository(initialCourses);

  console.log("\n--- Adding Course ---");
  await (async () => {
    console.log("Adding course with (ID : 7, Title 7, Published : YES, Price: 50.0) inside background task...");
    try {
      await repo.addCourse({ id: 7, title: "Title 7", published: Published.Yes, price: 50.0 });
    } catch (e) {
      console.error(`Error adding course: ${e.message}`);
    }
  })();

  console.log("\n--- Updating Course ---");
  await (async () => {
    console.log("Updating Title, Published status, and Price for Course with ID 1 inside background task...");
    const updates = {
      title: "Product 1",
      published: Published.No,
      price: 29.99,
    };
    try {
      await repo.updateCourse(1, updates);
    } catch (e) {
      console.error(`Error updating course: ${e.message}`);
    }
  })();

  console.log("\n--- Deleting Course (with ID 3) ---");
  try {
    await repo.deleteCourse(3);
    console.log("Successfully deleted course with ID 3");
  } catch (e) {
    console.error(`Error deleting course: ${e.message}`);
  }

  console.log("\n--- Fetching Courses ---");
  const finalCourses = await repo.fetchCourses({ sort: SortStrategy.IdAsc });
  finalCourses.forEach((c) => console.log(c));
}

main();