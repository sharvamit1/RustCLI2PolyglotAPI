use arc_swap::ArcSwap;
use std::sync::Arc;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Published {
    Yes,
    No,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SortStrategy {
    IdAsc,
    IdDesc,
    TitleAsc,
    TitleDesc,
    PriceAsc,
    PriceDesc,
}

#[derive(Debug, Clone)]
pub struct Course {
    pub id: i8,
    pub title: String,
    pub published: Published,
    pub price: f32,
}

#[derive(Debug, Clone, Default)]
pub struct PriceRange {
    pub min: Option<f32>,
    pub max: Option<f32>,
}

#[derive(Debug, Clone, Default)]
pub struct FilterCourses {
    pub ids: Option<Vec<i8>>,
    pub title: Option<String>,
    pub published: Option<Published>,
    pub price_range: Option<PriceRange>,
    pub sort: Option<SortStrategy>,
}

impl FilterCourses {
    pub fn default_filter() -> Self {
        Self {
            sort: Some(SortStrategy::IdAsc),
            ..Default::default()
        }
    }
}

#[derive(Debug, Clone, Default)]
pub struct UpdateCourse {
    pub title: Option<String>,
    pub published: Option<Published>,
    pub price: Option<f32>,
}

#[derive(Clone)]
pub struct CourseRepository {
    courses: Arc<ArcSwap<Vec<Course>>>,
}

impl CourseRepository {
    pub fn new(initial_data: Vec<Course>) -> Self {
        Self {
            courses: Arc::new(ArcSwap::from_pointee(initial_data)),
        }
    }

    pub fn fetch_courses(&self, filtered: FilterCourses) -> Vec<Course> {
        let current_snapshot = self.courses.load();
        let mut results: Vec<Course> = current_snapshot
            .iter()
            .filter(|course| {
                if let Some(ref ids) = filtered.ids {
                    if !ids.contains(&course.id) {
                        return false;
                    }
                }
                if let Some(pub_status) = filtered.published {
                    if course.published != pub_status {
                        return false;
                    }
                }
                if let Some(ref term) = filtered.title {
                    if !course.title.to_lowercase().contains(&term.to_lowercase()) {
                        return false;
                    }
                }
                if let Some(ref range) = filtered.price_range {
                    if let Some(min_val) = range.min {
                        if course.price < min_val {
                            return false;
                        }
                    }
                    if let Some(max_val) = range.max {
                        if course.price > max_val {
                            return false;
                        }
                    }
                }
                true
            })
            .cloned()
            .collect();

        let strategy = filtered.sort.unwrap_or(SortStrategy::IdAsc);
        match strategy {
            SortStrategy::IdAsc => results.sort_by(|a, b| a.id.cmp(&b.id)),
            SortStrategy::IdDesc => results.sort_by(|a, b| b.id.cmp(&a.id)),
            SortStrategy::TitleAsc => results.sort_by(|a, b| a.title.cmp(&b.title)),
            SortStrategy::TitleDesc => results.sort_by(|a, b| b.title.cmp(&a.title)),
            SortStrategy::PriceAsc => results.sort_by(|a, b| a.price.partial_cmp(&b.price).unwrap()),
            SortStrategy::PriceDesc => results.sort_by(|a, b| b.price.partial_cmp(&a.price).unwrap()),
        }

        results
    }

    pub async fn add_course(&self, course: Course) -> Result<(), String> {
        let mut check_err = Ok(());
        
        self.courses.rcu(|current| {
            if current.iter().any(|c| c.id == course.id) {
                check_err = Err(format!("Validation Error: Course with ID {} already exists!", course.id));
                return Arc::clone(current);
            }
            let mut proposed = (**current).clone();
            proposed.push(course.clone());
            Arc::new(proposed)
        });

        check_err
    }

    pub async fn update_course(&self, id: i8, updates: UpdateCourse) -> Result<(), String> {
        let mut check_err = Ok(());

        self.courses.rcu(|current| {
            let index = current.iter().position(|c| c.id == id);
            match index {
                None => {
                    check_err = Err(format!("Not Found Error: Course with ID {} does not exist!", id));
                    Arc::clone(current)
                }
                Some(idx) => {
                    let mut proposed = (**current).clone();
                    let target = &mut proposed[idx];
                    if let Some(t) = &updates.title {
                        target.title = t.clone();
                    }
                    if let Some(p) = updates.published {
                        target.published = p;
                    }
                    if let Some(pr) = updates.price {
                        target.price = pr;
                    }
                    Arc::new(proposed)
                }
            }
        });

        check_err
    }

    pub async fn delete_course(&self, id: i8) -> Result<(), String> {
        let mut check_err = Ok(());

        self.courses.rcu(|current| {
            let len_before = current.len();
            let mut proposed = (**current).clone();
            proposed.retain(|c| c.id != id);
            
            if proposed.len() == len_before {
                check_err = Err(format!("Not Found Error: Course with ID {} does not exist!", id));
                return Arc::clone(current);
            }
            Arc::new(proposed)
        });

        check_err
    }
}

#[tokio::main]
async fn main() {
    let initial_courses = vec![
        Course { id: 2, title: "Title 2".to_string(), published: Published::No, price: 10.0 },
        Course { id: 3, title: "Product 3".to_string(), published: Published::Yes, price: 45.5 },
        Course { id: 1, title: "Title 1".to_string(), published: Published::Yes, price: 25.0 },
        Course { id: 5, title: "Product 5".to_string(), published: Published::No, price: 120.0 },
        Course { id: 6, title: "Product 6".to_string(), published: Published::Yes, price: 75.0 },
        Course { id: 4, title: "Title 4".to_string(), published: Published::Yes, price: 9.99 },
    ];

    let repo = CourseRepository::new(initial_courses);

    println!("\n--- Adding Course ---");
    let repo_clone = repo.clone();
    let add_job = tokio::spawn(async move {
        println!("Adding course with (ID : 7, Title 7, Published : YES, Price: 50.0) inside background task...");
        if let Err(e) = repo_clone.add_course(Course {
            id: 7,
            title: "Title 7".to_string(),
            published: Published::Yes,
            price: 50.0,
        }).await {
            println!("Error adding course in background task: {}", e);
        }
    });
    let _ = add_job.await;

    println!("\n--- Updating Course ---");
    let repo_clone = repo.clone();
    let updates = UpdateCourse {
        title: Some("Product 1".to_string()),
        published: Some(Published::No),
        price: Some(29.99),
    };
    let update_job = tokio::spawn(async move {
        println!("Updating Title, Published status, and Price for Course with ID 1 inside background task...");
        if let Err(e) = repo_clone.update_course(1, updates).await {
            println!("Error updating course in background task: {}", e);
        }
    });
    let _ = update_job.await;

    println!("\n--- Deleting Course (with ID 3) ---");
    match repo.delete_course(3).await {
        Ok(_) => println!("Successfully deleted course with ID 3"),
        Err(e) => println!("Error deleting course: {}", e),
    }

    println!("\n--- Fetching Courses ---");
    let final_courses = repo.fetch_courses(FilterCourses::default_filter());
    for c in final_courses {
        println!("{:?}", c);
    }
}