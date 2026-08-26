package main

import (
	"errors"
	"fmt"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
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
	Id        int8
	Title     string
	Published Published
	Price     float32
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
	Title     *string
	Published *Published
	Price     *float32
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

	var wg sync.WaitGroup

	fmt.Println("\n--- Adding Course ---")
	wg.Add(1)
	go func() {
		defer wg.Done()
		fmt.Println("Adding course with (ID : 7, Title 7, Published : YES, Price: 50.0) inside goroutine...")
		err := repo.AddCourse(Course{Id: 7, Title: "Title 7", Published: YES, Price: 50.0})
		if err != nil {
			fmt.Println("Error adding course in goroutine:", err.Error())
		}
	}()
	wg.Wait()

	fmt.Println("\n--- Updating Course ---")
	newTitle := "Product 1"
	newPub := NO
	newPrice := float32(29.99)
	updates := UpdateCourse{Title: &newTitle, Published: &newPub, Price: &newPrice}

	wg.Add(1)
	go func() {
		defer wg.Done()
		fmt.Println("Updating Title, Published status, and Price for Course with ID 1 inside goroutine...")
		err := repo.UpdateCourse(1, updates)
		if err != nil {
			fmt.Println("Error updating course in goroutine:", err.Error())
		}
	}()
	wg.Wait()

	fmt.Println("\n--- Deleting Course (with ID 3) ---")
	err := repo.DeleteCourse(3)
	if err != nil {
		fmt.Println("Error deleting course:", err.Error())
	} else {
		fmt.Println("Successfully deleted course with ID 3")
	}

	fmt.Println("\n--- Fetching Courses ---")
	finalCourses := repo.FetchCourses(DefaultFilter())
	for _, c := range finalCourses {
		fmt.Printf("Course(Id: %d, Title: '%s', Published: %s, Price: %.2f)\n", c.Id, c.Title, c.Published, c.Price)
	}
}