const std = @import("std");
const Allocator = std.mem.Allocator;
const ArrayListUnmanaged = std.ArrayListUnmanaged;
const Thread = std.Thread;

pub const Published = enum {
    Yes,
    No,
};

pub const SortStrategy = enum {
    IdAsc,
    IdDesc,
    TitleAsc,
    TitleDesc,
    PriceAsc,
    PriceDesc,
};

pub const Course = struct {
    id: i8,
    title: []const u8,
    published: Published,
    price: f32,

    pub fn clone(self: Course, allocator: Allocator) !Course {
        return Course{
            .id = self.id,
            .title = try allocator.dupe(u8, self.title),
            .published = self.published,
            .price = self.price,
        };
    }

    pub fn deinit(self: Course, allocator: Allocator) void {
        allocator.free(self.title);
    }
};

pub const PriceRange = struct {
    min: ?f32 = null,
    max: ?f32 = null,
};

pub const FilterCourses = struct {
    ids: ?[]const i8 = null,
    title: ?[]const u8 = null,
    published: ?Published = null,
    price_range: ?PriceRange = null,
    sort: ?SortStrategy = .IdAsc,
};

pub const UpdateCourse = struct {
    title: ?[]const u8 = null,
    published: ?Published = null,
    price: ?f32 = null,
};

pub const CourseRepository = struct {
    allocator: Allocator,
    courses: ArrayListUnmanaged(Course),
    mutex: Thread.Mutex,

    pub fn init(allocator: Allocator) CourseRepository {
        return .{
            .allocator = allocator,
            .courses = ArrayListUnmanaged(Course){},
            .mutex = .{},
        };
    }

    pub fn deinit(self: *CourseRepository) void {
        for (self.courses.items) |course| {
            course.deinit(self.allocator);
        }
        self.courses.deinit(self.allocator);
    }

    fn courseLessThan(strategy: SortStrategy, a: Course, b: Course) bool {
        return switch (strategy) {
            .IdAsc => a.id < b.id,
            .IdDesc => a.id > b.id,
            .TitleAsc => std.mem.order(u8, a.title, b.title) == .lt,
            .TitleDesc => std.mem.order(u8, a.title, b.title) == .gt,
            .PriceAsc => a.price < b.price,
            .PriceDesc => a.price > b.price,
        };
    }

    pub fn fetchCourses(self: *CourseRepository, filtered: FilterCourses) !ArrayListUnmanaged(Course) {
        self.mutex.lock();
        defer self.mutex.unlock();

        var results = ArrayListUnmanaged(Course){};
        errdefer {
            for (results.items) |c| c.deinit(self.allocator);
            results.deinit(self.allocator);
        }

        for (self.courses.items) |course| {
            if (filtered.ids) |ids| {
                var found = false;
                for (ids) |id| {
                    if (course.id == id) {
                        found = true;
                        break;
                    }
                }
                if (!found) continue;
            }

            if (filtered.published) |pub_status| {
                if (course.published != pub_status) continue;
            }

            if (filtered.title) |term| {
                const title_lower = try self.allocator.alloc(u8, course.title.len);
                defer self.allocator.free(title_lower);
                _ = std.ascii.lowerString(title_lower, course.title);

                const term_lower = try self.allocator.alloc(u8, term.len);
                defer self.allocator.free(term_lower);
                _ = std.ascii.lowerString(term_lower, term);

                if (std.mem.indexOf(u8, title_lower, term_lower) == null) continue;
            }

            if (filtered.price_range) |range| {
                if (range.min) |min_val| {
                    if (course.price < min_val) continue;
                }
                if (range.max) |max_val| {
                    if (course.price > max_val) continue;
                }
            }

            const cloned_course = try course.clone(self.allocator);
            try results.append(self.allocator, cloned_course);
        }

        const strategy = filtered.sort orelse .IdAsc;
        std.sort.block(Course, results.items, strategy, courseLessThan);
        return results;
    }

    pub fn addCourse(self: *CourseRepository, course: Course) !void {
        self.mutex.lock();
        defer self.mutex.unlock();

        for (self.courses.items) |c| {
            if (c.id == course.id) {
                return error.DuplicateIdError;
            }
        }

        const cloned = try course.clone(self.allocator);
        try self.courses.append(self.allocator, cloned);
    }

    pub fn updateCourse(self: *CourseRepository, id: i8, updates: UpdateCourse) !void {
        self.mutex.lock();
        defer self.mutex.unlock();

        for (self.courses.items) |*course| {
            if (course.id == id) {
                if (updates.title) |t| {
                    self.allocator.free(course.title);
                    course.title = try self.allocator.dupe(u8, t);
                }
                if (updates.published) |p| course.published = p;
                if (updates.price) |pr| course.price = pr;
                return;
            }
        }
        return error.NotFoundError;
    }

    pub fn deleteCourse(self: *CourseRepository, id: i8) !void {
        self.mutex.lock();
        defer self.mutex.unlock();

        for (self.courses.items, 0..) |course, idx| {
            if (course.id == id) {
                const removed = self.courses.orderedRemove(idx);
                removed.deinit(self.allocator);
                return;
            }
        }
        return error.NotFoundError;
    }
};

fn addCourseJob(repo: *CourseRepository) void {
    std.debug.print("Adding course with (ID : 7) inside background thread...\n", .{});
    repo.addCourse(.{
        .id = 7,
        .title = "Title 7",
        .published = .Yes,
        .price = 50.0,
    }) catch |err| {
        std.debug.print("Error adding course: {}\n", .{err});
    };
}

fn updateCourseJob(repo: *CourseRepository) void {
    std.debug.print("Updating Course with ID 1 inside background thread...\n", .{});
    repo.updateCourse(1, .{
        .title = "Product 1",
        .published = .No,
        .price = 29.99,
    }) catch |err| {
        std.debug.print("Error updating course: {}\n", .{err});
    };
}

pub fn main() !void {
    var gpa = std.heap.GeneralPurposeAllocator(.{}){};
    defer _ = gpa.deinit();
    const allocator = gpa.allocator();

    var repo = CourseRepository.init(allocator);
    defer repo.deinit();

    try repo.addCourse(.{ .id = 2, .title = "Title 2", .published = .No, .price = 10.0 });
    try repo.addCourse(.{ .id = 3, .title = "Product 3", .published = .Yes, .price = 45.5 });
    try repo.addCourse(.{ .id = 1, .title = "Title 1", .published = .Yes, .price = 25.0 });
    try repo.addCourse(.{ .id = 5, .title = "Product 5", .published = .No, .price = 120.0 });
    try repo.addCourse(.{ .id = 6, .title = "Product 6", .published = .Yes, .price = 75.0 });
    try repo.addCourse(.{ .id = 4, .title = "Title 4", .published = .Yes, .price = 9.99 });

    std.debug.print("\n--- Adding Course ---\n", .{});
    const t1 = try Thread.spawn(.{}, addCourseJob, .{&repo});
    t1.join();

    std.debug.print("\n--- Updating Course ---\n", .{});
    const t2 = try Thread.spawn(.{}, updateCourseJob, .{&repo});
    t2.join();

    std.debug.print("\n--- Deleting Course (with ID 3) ---\n", .{});
    repo.deleteCourse(3) catch |err| {
        std.debug.print("Error deleting course: {}\n", .{err});
    };

    std.debug.print("\n--- Fetching Courses ---\n", .{});
    var final_courses = try repo.fetchCourses(.{ .sort = .IdAsc });
    defer {
        for (final_courses.items) |c| c.deinit(allocator);
        final_courses.deinit(allocator);
    }

    for (final_courses.items) |c| {
        std.debug.print("Course {{ id: {}, title: {s}, published: {s}, price: {d:.2} }}\n", .{
            c.id, c.title, @tagName(c.published), c.price,
        });
    }
}