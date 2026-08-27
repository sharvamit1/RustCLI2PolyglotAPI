package main

import (
	"errors"
	"fmt"
	"log"
	"sort"
	"strconv"
	"strings"
	"sync/atomic"

	"github.com/gofiber/fiber/v3"
	"github.com/gofiber/fiber/v3/middleware/cors"
)

type Published string

const (
	YES Published = "YES"
	NO  Published = "NO"
)

type SortStrategy int

const (
	ID_ASC SortStrategy = iota
	ID_DESC
	TITLE_ASC
	TITLE_DESC
	PRICE_ASC
	PRICE_DESC
)

type Course struct {
	Id        int8      `json:"id"`
	Title     string    `json:"title"`
	Published Published `json:"published"`
	Price     float32   `json:"price"`
}

type PriceRange struct {
	Min *float32
	Max *float32
}

type FilterCourses struct {
	Ids        []int8
	Title      *string
	Published  *Published
	PriceRange *PriceRange
	Sort       SortStrategy
}

func DefaultFilter() FilterCourses {
	return FilterCourses{Sort: ID_ASC}
}

type UpdateCourse struct {
	Title     *string    `json:"title"`
	Published *Published `json:"published"`
	Price     *float32   `json:"price"`
}

type CourseRepository struct {
	coursesRef atomic.Pointer[[]Course]
}

func NewCourseRepository(initialData []Course) *CourseRepository {
	repo := &CourseRepository{}
	dataCopy := make([]Course, len(initialData))
	copy(dataCopy, initialData)
	repo.coursesRef.Store(&dataCopy)
	return repo
}

func (repo *CourseRepository) FetchCourses(filtered FilterCourses) []Course {
	currentSnapshot := *repo.coursesRef.Load()
	var results []Course

	for _, course := range currentSnapshot {
		if filtered.Ids != nil {
			found := false
			for _, id := range filtered.Ids {
				if course.Id == id {
					found = true
					break
				}
			}
			if !found {
				continue
			}
		}
		if filtered.Published != nil && course.Published != *filtered.Published {
			continue
		}
		if filtered.Title != nil {
			if !strings.Contains(strings.ToLower(course.Title), strings.ToLower(*filtered.Title)) {
				continue
			}
		}
		if filtered.PriceRange != nil {
			if filtered.PriceRange.Min != nil && course.Price < *filtered.PriceRange.Min {
				continue
			}
			if filtered.PriceRange.Max != nil && course.Price > *filtered.PriceRange.Max {
				continue
			}
		}
		results = append(results, course)
	}

	switch filtered.Sort {
	case ID_ASC:
		sort.Slice(results, func(i, j int) bool { return results[i].Id < results[j].Id })
	case ID_DESC:
		sort.Slice(results, func(i, j int) bool { return results[i].Id > results[j].Id })
	case TITLE_ASC:
		sort.Slice(results, func(i, j int) bool { return results[i].Title < results[j].Title })
	case TITLE_DESC:
		sort.Slice(results, func(i, j int) bool { return results[i].Title > results[j].Title })
	case PRICE_ASC:
		sort.Slice(results, func(i, j int) bool { return results[i].Price < results[j].Price })
	case PRICE_DESC:
		sort.Slice(results, func(i, j int) bool { return results[i].Price > results[j].Price })
	}

	return results
}

func (repo *CourseRepository) UpdateCourse(id int8, updates UpdateCourse) error {
	for {
		currentSnapshotPtr := repo.coursesRef.Load()
		currentSnapshot := *currentSnapshotPtr
		targetIndex := -1

		for i, c := range currentSnapshot {
			if c.Id == id {
				targetIndex = i
				break
			}
		}

		if targetIndex == -1 {
			return errors.New("Not Found Error: Course does not exist")
		}

		proposedState := make([]Course, len(currentSnapshot))
		copy(proposedState, currentSnapshot)

		current := proposedState[targetIndex]
		updated := Course{
			Id:        current.Id,
			Title:     current.Title,
			Published: current.Published,
			Price:     current.Price,
		}

		if updates.Title != nil {
			updated.Title = *updates.Title
		}
		if updates.Published != nil {
			updated.Published = *updates.Published
		}
		if updates.Price != nil {
			updated.Price = *updates.Price
		}

		proposedState[targetIndex] = updated

		if repo.coursesRef.CompareAndSwap(currentSnapshotPtr, &proposedState) {
			return nil
		}
	}
}

func (repo *CourseRepository) AddCourse(course Course) error {
	for {
		currentSnapshotPtr := repo.coursesRef.Load()
		currentSnapshot := *currentSnapshotPtr

		for _, c := range currentSnapshot {
			if c.Id == course.Id {
				return errors.New("Validation Error: Course already exists")
			}
		}

		proposedState := make([]Course, len(currentSnapshot)+1)
		copy(proposedState, currentSnapshot)
		proposedState[len(currentSnapshot)] = course

		if repo.coursesRef.CompareAndSwap(currentSnapshotPtr, &proposedState) {
			return nil
		}
	}
}

func (repo *CourseRepository) DeleteCourse(id int8) error {
	for {
		currentSnapshotPtr := repo.coursesRef.Load()
		currentSnapshot := *currentSnapshotPtr
		targetIndex := -1

		for i, c := range currentSnapshot {
			if c.Id == id {
				targetIndex = i
				break
			}
		}

		if targetIndex == -1 {
			return errors.New("Not Found Error: Course does not exist")
		}

		proposedState := make([]Course, 0, len(currentSnapshot)-1)
		for i, c := range currentSnapshot {
			if i != targetIndex {
				proposedState = append(proposedState, c)
			}
		}

		if repo.coursesRef.CompareAndSwap(currentSnapshotPtr, &proposedState) {
			return nil
		}
	}
}

func main() {
	initialCourses := []Course{
		{Id: 2, Title: "Title 2", Published: NO, Price: 10.0},
		{Id: 3, Title: "Product 3", Published: YES, Price: 45.5},
		{Id: 1, Title: "Title 1", Published: YES, Price: 25.0},
		{Id: 5, Title: "Product 5", Published: NO, Price: 120.0},
		{Id: 6, Title: "Product 6", Published: YES, Price: 75.0},
		{Id: 4, Title: "Title 4", Published: YES, Price: 9.99},
	}

	repo := NewCourseRepository(initialCourses)

	app := fiber.New(fiber.Config{
		ErrorHandler: func(c fiber.Ctx, err error) error {
			return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{
				"error": err.Error(),
			})
		},
	})

	app.Use(cors.New())

	api := app.Group("/api/v1")
	coursesAPI := api.Group("/courses")

	coursesAPI.Get("", func(c fiber.Ctx) error {
		filter := DefaultFilter()

		if idsRaw := c.Query("ids"); idsRaw != "" {
			parts := strings.Split(idsRaw, ",")
			for _, p := range parts {
				if parsedID, err := strconv.ParseInt(strings.TrimSpace(p), 10, 8); err == nil {
					filter.Ids = append(filter.Ids, int8(parsedID))
				}
			}
		}

		if titleRaw := c.Query("title"); titleRaw != "" {
			filter.Title = &titleRaw
		}

		if pubRaw := c.Query("published"); pubRaw != "" {
			pubStatus := Published(strings.ToUpper(pubRaw))
			if pubStatus == YES || pubStatus == NO {
				filter.Published = &pubStatus
			}
		}

		var pRange PriceRange
		hasPriceFilter := false

		if minRaw := c.Query("min_price"); minRaw != "" {
			if parsedMin, err := strconv.ParseFloat(minRaw, 32); err == nil {
				minVal := float32(parsedMin)
				pRange.Min = &minVal
				hasPriceFilter = true
			}
		}
		if maxRaw := c.Query("max_price"); maxRaw != "" {
			if parsedMax, err := strconv.ParseFloat(maxRaw, 32); err == nil {
				maxVal := float32(parsedMax)
				pRange.Max = &maxVal
				hasPriceFilter = true
			}
		}
		if hasPriceFilter {
			filter.PriceRange = &pRange
		}

		if sortRaw := strings.ToLower(c.Query("sort")); sortRaw != "" {
			switch sortRaw {
			case "id_asc":
				filter.Sort = ID_ASC
			case "id_desc":
				filter.Sort = ID_DESC
			case "title_asc":
				filter.Sort = TITLE_ASC
			case "title_desc":
				filter.Sort = TITLE_DESC
			case "price_asc":
				filter.Sort = PRICE_ASC
			case "price_desc":
				filter.Sort = PRICE_DESC
			}
		}

		results := repo.FetchCourses(filter)
		return c.JSON(results)
	})

	coursesAPI.Post("", func(c fiber.Ctx) error {
		var payload Course
		if err := c.Bind().Body(&payload); err != nil {
			return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "Invalid request schema payload"})
		}

		if payload.Id <= 0 || payload.Title == "" {
			return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "Invalid properties initialization data constraints"})
		}

		if payload.Published != YES && payload.Published != NO {
			payload.Published = NO
		}

		if err := repo.AddCourse(payload); err != nil {
			return c.Status(fiber.StatusConflict).JSON(fiber.Map{"error": err.Error()})
		}

		return c.Status(fiber.StatusCreated).JSON(fiber.Map{"status": "success", "message": fmt.Sprintf("Course with ID %d created successfully", payload.Id)})
	})

	coursesAPI.Patch("/:id", func(c fiber.Ctx) error {
		idParam, err := strconv.ParseInt(c.Params("id"), 10, 8)
		if err != nil {
			return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "Invalid path sequence id attribute"})
		}

		var updates UpdateCourse
		if err := c.Bind().Body(&updates); err != nil {
			return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "Malformatted payload structure"})
		}

		if err := repo.UpdateCourse(int8(idParam), updates); err != nil {
			if strings.Contains(err.Error(), "Not Found Error") {
				return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"error": err.Error()})
			}
			return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"error": err.Error()})
		}

		return c.Status(fiber.StatusOK).JSON(fiber.Map{"status": "success", "message": fmt.Sprintf("Course with ID %d updated successfully", idParam)})
	})

	coursesAPI.Delete("/:id", func(c fiber.Ctx) error {
		idParam, err := strconv.ParseInt(c.Params("id"), 10, 8)
		if err != nil {
			return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "Invalid parameter ID syntax"})
		}

		if err := repo.DeleteCourse(int8(idParam)); err != nil {
			if strings.Contains(err.Error(), "Not Found Error") {
				return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"error": err.Error()})
			}
			return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"error": err.Error()})
		}

		return c.Status(fiber.StatusOK).JSON(fiber.Map{"status": "success", "message": fmt.Sprintf("Course with ID %d deleted successfully", idParam)})
	})

	log.Println("Starting server over port :3000...")
	if err := app.Listen(":3000"); err != nil {
		log.Fatalf("Server startup dropped: %v", err)
	}
}