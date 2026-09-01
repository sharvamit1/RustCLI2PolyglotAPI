using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;

namespace CourseManagement;

public enum Published 
{ 
    Yes, 
    No 
}

public enum SortStrategy
{
    IdAsc, 
    IdDesc,
    TitleAsc, 
    TitleDesc,
    PriceAsc, 
    PriceDesc
}

public record Course(
    sbyte Id, 
    string Title, 
    Published Published, 
    float Price
);

public record PriceRange(
    float? Min = null, 
    float? Max = null
);

public record FilterCourses(
    List<sbyte>? Ids = null,
    string? Title = null,
    Published? Published = null,
    PriceRange? PriceRange = null,
    SortStrategy Sort = SortStrategy.IdAsc
)
{
    public static FilterCourses DefaultFilter() => new();
}

public record UpdateCourse(
    string? Title = null,
    Published? Published = null,
    float? Price = null
);

public class CourseRepository(IEnumerable<Course> initialData)
{
    private readonly List<Course> _courses = initialData.ToList();
    private readonly SemaphoreSlim _lock = new(1, 1);

    public async Task<List<Course>> FetchCoursesAsync(FilterCourses filtered)
    {
        await _lock.WaitAsync();
        try
        {
            var query = _courses.AsEnumerable();

            if (filtered.Ids is { Count: > 0 } ids)
            {
                query = query.Where(c => ids.Contains(c.Id));
            }

            if (filtered.Published is { } pubStatus)
            {
                query = query.Where(c => c.Published == pubStatus);
            }

            if (!string.IsNullOrWhiteSpace(filtered.Title))
            {
                query = query.Where(c => c.Title.Contains(filtered.Title, StringComparison.OrdinalIgnoreCase));
            }

            if (filtered.PriceRange is { } range)
            {
                if (range.Min is { } minVal) query = query.Where(c => c.Price >= minVal);
                if (range.Max is { } maxVal) query = query.Where(c => c.Price <= maxVal);
            }

            query = filtered.Sort switch
            {
                SortStrategy.IdAsc     => query.OrderBy(c => c.Id),
                SortStrategy.IdDesc    => query.OrderByDescending(c => c.Id),
                SortStrategy.TitleAsc  => query.OrderBy(c => c.Title, StringComparer.Ordinal),
                SortStrategy.TitleDesc => query.OrderByDescending(c => c.Title, StringComparer.Ordinal),
                SortStrategy.PriceAsc  => query.OrderBy(c => c.Price),
                SortStrategy.PriceDesc => query.OrderByDescending(c => c.Price),
                _                      => query.OrderBy(c => c.Id)
            };

            return query.ToList();
        }
        finally
        {
            _lock.Release();
        }
    }

    public async Task AddCourseAsync(Course course)
    {
        await _lock.WaitAsync();
        try
        {
            if (_courses.Any(c => c.Id == course.Id))
            {
                throw new InvalidOperationException($"Validation Error: Course with ID {course.Id} already exists!");
            }

            _courses.Add(course);
        }
        finally
        {
            _lock.Release();
        }
    }

    public async Task UpdateCourseAsync(sbyte id, UpdateCourse updates)
    {
        await _lock.WaitAsync();
        try
        {
            int index = _courses.FindIndex(c => c.Id == id);
            if (index == -1)
            {
                throw new KeyNotFoundException($"Not Found Error: Course with ID {id} does not exist!");
            }

            var current = _courses[index];
            
            _courses[index] = current with
            {
                Title = updates.Title ?? current.Title,
                Published = updates.Published ?? current.Published,
                Price = updates.Price ?? current.Price
            };
        }
        finally
        {
            _lock.Release();
        }
    }

    public async Task DeleteCourseAsync(sbyte id)
    {
        await _lock.WaitAsync();
        try
        {
            int removedCount = _courses.RemoveAll(c => c.Id == id);
            if (removedCount == 0)
            {
                throw new KeyNotFoundException($"Not Found Error: Course with ID {id} does not exist!");
            }
        }
        finally
        {
            _lock.Release();
        }
    }
}

public static class Program
{
    public static async Task Main()
    {
        var initialCourses = new List<Course>
        {
            new(2, "Title 2", Published.No, 10.0f),
            new(3, "Product 3", Published.Yes, 45.5f),
            new(1, "Title 1", Published.Yes, 25.0f),
            new(5, "Product 5", Published.No, 120.0f),
            new(6, "Product 6", Published.Yes, 75.0f),
            new(4, "Title 4", Published.Yes, 9.99f)
        };

        var repo = new CourseRepository(initialCourses);

        Console.WriteLine("\n--- Adding Course ---");
        var addJob = Task.Run(async () =>
        {
            Console.WriteLine("Adding course with (ID : 7, Title 7, Published : YES, Price: 50.0) inside background task...");
            try
            {
                await repo.AddCourseAsync(new Course(7, "Title 7", Published.Yes, 50.0f));
            }
            catch (Exception e)
            {
                Console.WriteLine($"Error adding course in background task: {e.Message}");
            }
        });
        await addJob;

        Console.WriteLine("\n--- Updating Course ---");
        var updates = new UpdateCourse(Title: "Product 1", Published: Published.No, Price: 29.99f);
        var updateJob = Task.Run(async () =>
        {
            Console.WriteLine("Updating Title, Published status, and Price for Course with ID 1 inside background task...");
            try
            {
                await repo.UpdateCourseAsync(1, updates);
            }
            catch (Exception e)
            {
                Console.WriteLine($"Error updating course in background task: {e.Message}");
            }
        });
        await updateJob;

        Console.WriteLine("\n--- Deleting Course (with ID 3) ---");
        try
        {
            await repo.DeleteCourseAsync(3);
            Console.WriteLine("Successfully deleted course with ID 3");
        }
        catch (Exception e)
        {
            Console.WriteLine($"Error deleting course: {e.Message}");
        }

        Console.WriteLine("\n--- Fetching Courses ---");
        var finalCourses = await repo.FetchCoursesAsync(FilterCourses.DefaultFilter());
        foreach (var c in finalCourses)
        {
            Console.WriteLine(c);
        }
    }
}