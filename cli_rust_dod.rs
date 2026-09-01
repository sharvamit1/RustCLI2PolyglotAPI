use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::RwLock;

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

#[derive(Debug, Default)]
pub struct CourseStorage {
    pub ids: Vec<i8>,
    pub titles: Vec<String>,
    pub published_statuses: Vec<Published>,
    pub prices: Vec<f32>,
    pub id_to_offset: HashMap<i8, usize>,
}

#[derive(Clone)]
pub struct CourseRepository {
    storage: Arc<RwLock<CourseStorage>>,
}

impl CourseRepository {
    pub fn new(initial_data: Vec<Course>) -> Self {
        let mut storage = CourseStorage::default();
        for course in initial_data {
            let offset = storage.ids.len();
            storage.id_to_offset.insert(course.id, offset);
            storage.ids.push(course.id);
            storage.titles.push(course.title);
            storage.published_statuses.push(course.published);
            storage.prices.push(course.price);
        }
        Self {
            storage: Arc::new(RwLock::new(storage)),
        }
    }

    pub async fn fetch_courses(&self, filtered: FilterCourses) -> Vec<Course> {
        let guard = self.storage.read().await;
        let mut matched_indices = Vec::with_capacity(guard.ids.len());

        for i in 0..guard.ids.len() {
            if let Some(ref ids) = filtered.ids {
                if !ids.contains(&guard.ids[i]) { continue; }
            }
            if let Some(pub_status) = filtered.published {
                if guard.published_statuses[i] != pub_status { continue; }
            }
            if let Some(ref range) = filtered.price_range {
                if let Some(min_val) = range.min {
                    if guard.prices[i] < min_val { continue; }
                }
                if let Some(max_val) = range.max {
                    if guard.prices[i] > max_val { continue; }
                }
            }
            if let Some(ref term) = filtered.title {
                if !guard.titles[i].to_lowercase().contains(&term.to_lowercase()) {
                    continue;
                }
            }
            matched_indices.push(i);
        }

        let mut results: Vec<Course> = matched_indices
            .into_iter()
            .map(|i| Course {
                id: guard.ids[i],
                title: guard.titles[i].clone(),
                published: guard.published_statuses[i],
                price: guard.prices[i],
            })
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
        let mut guard = self.storage.write().await;
        
        if guard.id_to_offset.contains_key(&course.id) {
            return Err(format!("Validation Error: Course with ID {} already exists!", course.id));
        }

        let new_offset = guard.ids.len();
        guard.id_to_offset.insert(course.id, new_offset);
        guard.ids.push(course.id);
        guard.titles.push(course.title);
        guard.published_statuses.push(course.published);
        guard.prices.push(course.price);
        Ok(())
    }

    pub async fn update_course(&self, id: i8, updates: UpdateCourse) -> Result<(), String> {
        let mut guard = self.storage.write().await;
        
        match guard.id_to_offset.get(&id).copied() {
            None => Err(format!("Not Found Error: Course with ID {} does not exist!", id)),
            Some(offset) => {
                if let Some(t) = updates.title {
                    guard.titles[offset] = t;
                }
                if let Some(p) = updates.published {
                    guard.published_statuses[offset] = p;
                }
                if let Some(pr) = updates.price {
                    guard.prices[offset] = pr;
                }
                Ok(())
            }
        }
    }

    pub async fn delete_course(&self, id: i8) -> Result<(), String> {
        let mut guard = self.storage.write().await;
        
        match guard.id_to_offset.remove(&id) {
            None => Err(format!("Not Found Error: Course with ID {} does not exist!", id)),
            Some(removed_offset) => {
                let last_offset = guard.ids.len() - 1;
                
                if removed_offset != last_offset {
                    let moved_id = guard.ids[last_offset];
                    
                    guard.ids.swap(removed_offset, last_offset);
                    guard.titles.swap(removed_offset, last_offset);
                    guard.published_statuses.swap(removed_offset, last_offset);
                    guard.prices.swap(removed_offset, last_offset);
                    
                    guard.id_to_offset.insert(moved_id, removed_offset);
                }

                guard.ids.pop();
                guard.titles.pop();
                guard.published_statuses.pop();
                guard.prices.pop();
                Ok(())
            }
        }
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
    let final_courses = repo.fetch_courses(FilterCourses::default_filter()).await;
    for c in final_courses {
        println!("{:?}", c);
    }
}