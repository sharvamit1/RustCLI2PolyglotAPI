import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public class Main {

    public enum Published {
        YES, NO
    }

    public record Course(
        byte id,
        String title,
        Published published,
        float price
    ) {}

    public enum SortStrategy {
        ID_ASC, ID_DESC, TITLE_ASC, TITLE_DESC, PRICE_ASC, PRICE_DESC
    }

    public record PriceRange(Float min, Float max) {}

    public record FilterCourses(
        List<Byte> ids,
        String title,
        Published published,
        PriceRange priceRange,
        SortStrategy sort
    ) {
        public FilterCourses {
            if (sort == null) sort = SortStrategy.ID_ASC;
        }

        public static FilterCourses defaultFilter() {
            return new FilterCourses(null, null, null, null, SortStrategy.ID_ASC);
        }
    }

    public record UpdateCourse(
        String title,
        Published published,
        Float price
    ) {}

    public static class CourseRepository {

        private final AtomicReference<List<Course>> coursesRef;

        public CourseRepository(List<Course> initialData) {
            this.coursesRef = new AtomicReference<>(List.copyOf(initialData));
        }

        public List<Course> fetchCourses(FilterCourses filtered) {
            List<Course> currentSnapshot = coursesRef.get();

            List<Course> results = currentSnapshot.stream()
                .filter(course -> {
                    if (filtered.ids() != null && !filtered.ids().contains(course.id())) {
                        return false;
                    }
                    if (filtered.published() != null && course.published() != filtered.published()) {
                        return false;
                    }
                    if (filtered.title() != null) {
                        if (!course.title().toLowerCase().contains(filtered.title().toLowerCase())) {
                            return false;
                        }
                    }
                    if (filtered.priceRange() != null) {
                        if (filtered.priceRange().min() != null && course.price() < filtered.priceRange().min()) {
                            return false;
                        }
                        if (filtered.priceRange().max() != null && course.price() > filtered.priceRange().max()) {
                            return false;
                        }
                    }
                    return true;
                })
                .collect(Collectors.toList());

            SortStrategy strategy = filtered.sort() != null ? filtered.sort() : SortStrategy.ID_ASC;
            switch (strategy) {
                case ID_ASC     -> results.sort(Comparator.comparing(Course::id));
                case ID_DESC    -> results.sort(Comparator.comparing(Course::id).reversed());
                case TITLE_ASC  -> results.sort(Comparator.comparing(Course::title));
                case TITLE_DESC -> results.sort(Comparator.comparing(Course::title).reversed());
                case PRICE_ASC  -> results.sort(Comparator.comparing(Course::price));
                case PRICE_DESC -> results.sort(Comparator.comparing(Course::price).reversed());
            }

            return results;
        }

        public void updateCourse(byte id, UpdateCourse updates) throws Exception {
            List<Course> currentSnapshot;
            List<Course> proposedState;
            boolean found;

            do {
                currentSnapshot = coursesRef.get();
                proposedState = new ArrayList<>(currentSnapshot);
                found = false;

                for (int i = 0; i < proposedState.size(); i++) {
                    Course current = proposedState.get(i);
                    if (current.id() == id) {
                        found = true;
                        Course updated = new Course(
                            current.id(),
                            updates.title() != null ? updates.title() : current.title(),
                            updates.published() != null ? updates.published() : current.published(),
                            updates.price() != null ? updates.price() : current.price()
                        );
                        proposedState.set(i, updated);
                        break;
                    }
                }

                if (!found) {
                    throw new NoSuchElementException("Not Found Error: Course with ID " + id + " does not exist!");
                }

            } while (!coursesRef.compareAndSet(currentSnapshot, List.copyOf(proposedState)));
        }

        public void addCourse(Course course) throws Exception {
            List<Course> currentSnapshot;
            List<Course> proposedState;

            do {
                currentSnapshot = coursesRef.get();
                boolean exists = currentSnapshot.stream().anyMatch(c -> c.id() == course.id());
                if (exists) {
                    throw new IllegalArgumentException("Validation Error: Course with ID " + course.id() + " already exists!");
                }

                proposedState = new ArrayList<>(currentSnapshot);
                proposedState.add(course);

            } while (!coursesRef.compareAndSet(currentSnapshot, List.copyOf(proposedState)));
        }

        public void deleteCourse(byte id) throws Exception {
            List<Course> currentSnapshot;
            List<Course> proposedState;
            boolean wasRemoved;

            do {
                currentSnapshot = coursesRef.get();
                proposedState = new ArrayList<>(currentSnapshot);
                wasRemoved = proposedState.removeIf(course -> course.id() == id);

                if (!wasRemoved) {
                    throw new NoSuchElementException("Not Found Error: Course with ID " + id + " does not exist!");
                }

            } while (!coursesRef.compareAndSet(currentSnapshot, List.copyOf(proposedState)));
        }
    }

    public static void main(String[] args) throws Exception {
        List<Course> initialCourses = List.of(
            new Course((byte) 2, "Title 2", Published.NO, 10.0f),
            new Course((byte) 3, "Product 3", Published.YES, 45.5f),
            new Course((byte) 1, "Title 1", Published.YES, 25.0f),
            new Course((byte) 5, "Product 5", Published.NO, 120.0f),
            new Course((byte) 6, "Product 6", Published.YES, 75.0f),
            new Course((byte) 4, "Title 4", Published.YES, 9.99f)
        );

        CourseRepository repo = new CourseRepository(initialCourses);

        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {

            System.out.println("\n--- Adding Course ---");
            CompletableFuture<Void> addJob = CompletableFuture.runAsync(() -> {
                System.out.println("Adding course with (ID : 7, Title 7, Published : YES, Price: 50.0) inside background thread...");
                try {
                    repo.addCourse(new Course((byte) 7, "Title 7", Published.YES, 50.0f));
                } catch (Exception e) {
                    System.out.println("Error adding course in background thread: " + e.getMessage());
                }
            }, executor);
            addJob.join();

            System.out.println("\n--- Updating Course ---");
            UpdateCourse updates = new UpdateCourse("Product 1", Published.NO, 29.99f);
            CompletableFuture<Void> updateJob = CompletableFuture.runAsync(() -> {
                System.out.println("Updating Title, Published status, and Price for Course with ID 1 inside background thread...");
                try {
                    repo.updateCourse((byte) 1, updates);
                } catch (Exception e) {
                    System.out.println("Error updating course in background thread: " + e.getMessage());
                }
            }, executor);
            updateJob.join();
        }

        System.out.println("\n--- Deleting Course (with ID 3) ---");
        try {
            repo.deleteCourse((byte) 3);
            System.out.println("Successfully deleted course with ID 3");
        } catch (Exception e) {
            System.out.println("Error deleting course: " + e.getMessage());
        }

        System.out.println("\n--- Fetching Courses (Unfiltered) ---");
        repo.fetchCourses(FilterCourses.defaultFilter()).forEach(System.out::println);
    }
}