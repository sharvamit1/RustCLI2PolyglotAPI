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
    pub active_ids: Vec<i8>,
    pub active_titles: Vec<String>,
    pub active_prices: Vec<f32>,
    pub active_id_to_offset: HashMap<i8, usize>,
    pub draft_courses: HashMap<i8, (String, f32)>,
}

#[derive(Clone)]
pub struct CourseRepository {
    storage: Arc<RwLock<CourseStorage>>,
}

impl CourseRepository {
    pub fn new(initial_data: Vec<Course>) -> Self {
        let mut storage = CourseStorage::default();
        for course in initial_data {
            match course.published {
                Published::Yes => {
                    let offset = storage.active_ids.len();
                    storage.active_id_to_offset.insert(course.id, offset);
                    storage.active_ids.push(course.id);
                    storage.active_titles.push(course.title);
                    storage.active_prices.push(course.price);
                }
                Published::No => {
                    storage.draft_courses.insert(course.id, (course.title, course.price));
                }
            }
        }
        Self {
            storage: Arc::new(RwLock::new(storage)),
        }
    }

    pub async fn fetch_courses(&self, filtered: FilterCourses) -> Vec<Course> {
        let guard = self.storage.read().await;
        let mut results = Vec::new();
        let lower_term = filtered.title.as_ref().map(|t| t.to_lowercase());

        if filtered.published == Some(Published::Yes) || filtered.published.is_none() {
            for i in 0..guard.active_ids.len() {
                if let Some(ref ids) = filtered.ids {
                    if !ids.contains(&guard.active_ids[i]) { continue; }
                }
                if let Some(ref range) = filtered.price_range {
                    if let Some(min_val) = range.min {
                        if guard.active_prices[i] < min_val { continue; }
                    }
                    if let Some(max_val) = range.max {
                        if guard.active_prices[i] > max_val { continue; }
                    }
                }
                if let Some(ref term) = lower_term {
                    if !guard.active_titles[i].to_lowercase().contains(term) {
                        continue;
                    }
                }
                results.push(Course {
                    id: guard.active_ids[i],
                    title: guard.active_titles[i].clone(),
                    published: Published::Yes,
                    price: guard.active_prices[i],
                });
            }
        }

        if filtered.published == Some(Published::No) || filtered.published.is_none() {
            for (&id, (title, price)) in &guard.draft_courses {
                if let Some(ref ids) = filtered.ids {
                    if !ids.contains(&id) { continue; }
                }
                if let Some(ref range) = filtered.price_range {
                    if let Some(min_val) = range.min {
                        if *price < min_val { continue; }
                    }
                    if let Some(max_val) = range.max {
                        if *price > max_val { continue; }
                    }
                }
                if let Some(ref term) = lower_term {
                    if !title.to_lowercase().contains(term) { continue; }
                }
                results.push(Course {
                    id,
                    title: title.clone(),
                    published: Published::No,
                    price: *price,
                });
            }
        }

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
        
        if guard.active_id_to_offset.contains_key(&course.id) || guard.draft_courses.contains_key(&course.id) {
            return Err(format!("Validation Error: Course with ID {} already exists!", course.id));
        }

        match course.published {
            Published::Yes => {
                let new_offset = guard.active_ids.len();
                guard.active_id_to_offset.insert(course.id, new_offset);
                guard.active_ids.push(course.id);
                guard.active_titles.push(course.title);
                guard.active_prices.push(course.price);
            }
            Published::No => {
                guard.draft_courses.insert(course.id, (course.title, course.price));
            }
        }
        Ok(())
    }

    pub async fn update_course(&self, id: i8, updates: UpdateCourse) -> Result<(), String> {
        let mut guard = self.storage.write().await;
        
        if let Some(&offset) = guard.active_id_to_offset.get(&id) {
            if let Some(t) = updates.title { guard.active_titles[offset] = t; }
            if let Some(pr) = updates.price { guard.active_prices[offset] = pr; }
            
            if let Some(Published::No) = updates.published {
                let title = guard.active_titles[offset].clone();
                let price = guard.active_prices[offset];
                
                let last_offset = guard.active_ids.len() - 1;
                if offset != last_offset {
                    let moved_id = guard.active_ids[last_offset];
                    guard.active_ids.swap(offset, last_offset);
                    guard.active_titles.swap(offset, last_offset);
                    guard.active_prices.swap(offset, last_offset);
                    guard.active_id_to_offset.insert(moved_id, offset);
                }
                guard.active_ids.pop();
                guard.active_titles.pop();
                guard.active_prices.pop();
                guard.active_id_to_offset.remove(&id);

                guard.draft_courses.insert(id, (title, price));
            }
            return Ok(());
        }

        if let Some(draft) = guard.draft_courses.get_mut(&id) {
            if let Some(t) = updates.title { draft.0 = t; }
            if let Some(pr) = updates.price { draft.1 = pr; }

            if let Some(Published::Yes) = updates.published {
                let (title, price) = guard.draft_courses.remove(&id).unwrap();
                let new_offset = guard.active_ids.len();
                guard.active_id_to_offset.insert(id, new_offset);
                guard.active_ids.push(id);
                guard.active_titles.push(title);
                guard.active_prices.push(price);
            }
            return Ok(());
        }

        Err(format!("Not Found Error: Course with ID {} does not exist!", id))
    }

    pub async fn delete_course(&self, id: i8) -> Result<(), String> {
        let mut guard = self.storage.write().await;
        
        if let Some(offset) = guard.active_id_to_offset.remove(&id) {
            let last_offset = guard.active_ids.len() - 1;
            if offset != last_offset {
                let moved_id = guard.active_ids[last_offset];
                guard.active_ids.swap(offset, last_offset);
                guard.active_titles.swap(offset, last_offset);
                guard.active_prices.swap(offset, last_offset);
                guard.active_id_to_offset.insert(moved_id, offset);
            }
            guard.active_ids.pop();
            guard.active_titles.pop();
            guard.active_prices.pop();
            return Ok(());
        }

        if guard.draft_courses.remove(&id).is_some() {
            return Ok(());
        }

        Err(format!("Not Found Error: Course with ID {} does not exist!", id))
    }
}

#[tokio::main]
async fn main() {
    let initial_courses = vec![
        Course { id: 2, title: "Draft Course 2".to_string(), published: Published::No, price: 10.0 },
        Course { id: 3, title: "Live Product 3".to_string(), published: Published::Yes, price: 45.5 },
        Course { id: 1, title: "Live Title 1".to_string(), published: Published::Yes, price: 25.0 },
        Course { id: 5, title: "Draft Product 5".to_string(), published: Published::No, price: 120.0 },
    ];

    let repo = CourseRepository::new(initial_courses);

    println!("--- Fetching Only Active Courses ---");
    let mut filter = FilterCourses::default_filter();
    filter.published = Some(Published::Yes);
    let active_courses = repo.fetch_courses(filter).await;
    for c in active_courses {
        println!("{:?}", c);
    }

    println!("\n--- Publishing Course ID 5 ---");
    repo.update_course(5, UpdateCourse {
        published: Some(Published::Yes),
        ..Default::default()
    }).await.unwrap();

    println!("\n--- Fetching All Courses After Update ---");
    let all_courses = repo.fetch_courses(FilterCourses::default_filter()).await;
    for c in all_courses {
        println!("{:?}", c);
    }
}