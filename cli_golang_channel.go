package main

import (
	"errors"
	"fmt"
	"sort"
	"strings"
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

type CommandType int

const (
	CmdFetch CommandType = iota
	CmdAdd
	CmdUpdate
	CmdDelete
)

type Command struct {
	Type      CommandType
	Filter    FilterCourses
	Course    Course
	CourseId  int8
	Updates   UpdateCourse
	FetchRes  chan []Course
	MutateRes chan error
}

type CourseRepository struct {
	cmdChan chan Command
}

func NewCourseRepository(initialData []Course) *CourseRepository {
	repo := &CourseRepository{
		cmdChan: make(chan Command),
	}
	go repo.startStateLoop(initialData)
	return repo
}

func (repo *CourseRepository) startStateLoop(initialData []Course) {
	state := make([]Course, len(initialData))
	copy(state, initialData)

	for cmd := range repo.cmdChan {
		switch cmd.Type {
		case CmdFetch:
			var results []Course
			for _, course := range state {
				if cmd.Filter.Ids != nil {
					found := false
					for _, id := range cmd.Filter.Ids {
						if course.Id == id {
							found = true
							break
						}
					}
					if !found {
						continue
					}
				}
				if cmd.Filter.Published != nil && course.Published != *cmd.Filter.Published {
					continue
				}
				if cmd.Filter.Title != nil {
					if !strings.Contains(strings.ToLower(course.Title), strings.ToLower(*cmd.Filter.Title)) {
						continue
					}
				}
				if cmd.Filter.PriceRange != nil {
					if cmd.Filter.PriceRange.Min != nil && course.Price < *cmd.Filter.PriceRange.Min {
						continue
					}
					if cmd.Filter.PriceRange.Max != nil && course.Price > *cmd.Filter.PriceRange.Max {
						continue
					}
				}
				results = append(results, course)
			}

			switch cmd.Filter.Sort {
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
			cmd.FetchRes <- results

		case CmdAdd:
			exists := false
			for _, c := range state {
				if c.Id == cmd.Course.Id {
					exists = true
					break
				}
			}
			if exists {
				cmd.MutateRes <- errors.New("Validation Error: Course already exists")
				continue
			}
			state = append(state, cmd.Course)
			cmd.MutateRes <- nil

		case CmdUpdate:
			targetIndex := -1
			for i, c := range state {
				if c.Id == cmd.CourseId {
					targetIndex = i
					break
				}
			}
			if targetIndex == -1 {
				cmd.MutateRes <- errors.New("Not Found Error: Course does not exist")
				continue
			}

			if cmd.Updates.Title != nil {
				state[targetIndex].Title = *cmd.Updates.Title
			}
			if cmd.Updates.Published != nil {
				state[targetIndex].Published = *cmd.Updates.Published
			}
			if cmd.Updates.Price != nil {
				state[targetIndex].Price = *cmd.Updates.Price
			}
			cmd.MutateRes <- nil

		case CmdDelete:
			targetIndex := -1
			for i, c := range state {
				if c.Id == cmd.CourseId {
					targetIndex = i
					break
				}
			}
			if targetIndex == -1 {
				cmd.MutateRes <- errors.New("Not Found Error: Course does not exist")
				continue
			}
			state = append(state[:targetIndex], state[targetIndex+1:]...)
			cmd.MutateRes <- nil
		}
	}
}

func (repo *CourseRepository) FetchCourses(filtered FilterCourses) []Course {
	resChan := make(chan []Course)
	repo.cmdChan <- Command{
		Type:     CmdFetch,
		Filter:   filtered,
		FetchRes: resChan,
	}
	return <-resChan
}

func (repo *CourseRepository) AddCourse(course Course) error {
	resChan := make(chan error)
	repo.cmdChan <- Command{
		Type:      CmdAdd,
		Course:    course,
		MutateRes: resChan,
	}
	return <-resChan
}

func (repo *CourseRepository) UpdateCourse(id int8, updates UpdateCourse) error {
	resChan := make(chan error)
	repo.cmdChan <- Command{
		Type:      CmdUpdate,
		CourseId:  id,
		Updates:   updates,
		MutateRes: resChan,
	}
	return <-resChan
}

func (repo *CourseRepository) DeleteCourse(id int8) error {
	resChan := make(chan error)
	repo.cmdChan <- Command{
		Type:      CmdDelete,
		CourseId:  id,
		MutateRes: resChan,
	}
	return <-resChan
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

	fmt.Println("\n--- Adding Course ---")
	doneAdd := make(chan struct{})
	go func() {
		fmt.Println("Adding course with (ID : 7, Title 7, Published : YES, Price: 50.0) inside goroutine...")
		err := repo.AddCourse(Course{Id: 7, Title: "Title 7", Published: YES, Price: 50.0})
		if err != nil {
			fmt.Println("Error adding course in goroutine:", err.Error())
		}
		close(doneAdd)
	}()
	<-doneAdd

	fmt.Println("\n--- Updating Course ---")
	newTitle := "Product 1"
	newPub := NO
	newPrice := float32(29.99)
	updates := UpdateCourse{Title: &newTitle, Published: &newPub, Price: &newPrice}

	doneUpdate := make(chan struct{})
	go func() {
		fmt.Println("Updating Title, Published status, and Price for Course with ID 1 inside goroutine...")
		err := repo.UpdateCourse(1, updates)
		if err != nil {
			fmt.Println("Error updating course in goroutine:", err.Error())
		}
		close(doneUpdate)
	}()
	<-doneUpdate

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