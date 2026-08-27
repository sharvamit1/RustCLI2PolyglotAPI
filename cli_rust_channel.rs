use tokio::sync::{mpsc, oneshot};

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

pub enum Command {
    Fetch {
        filter: FilterCourses,
        respond_to: oneshot::Sender<Vec<Course>>,
    },
    Add {
        course: Course,
        respond_to: oneshot::Sender<Result<(), String>>,
    },
    Update {
        id: i8,
        updates: UpdateCourse,
        respond_to: oneshot::Sender<Result<(), String>>,
    },
    Delete {
        id: i8,
        respond_to: oneshot::Sender<Result<(), String>>,
    },
}

#[derive(Clone)]
pub struct CourseRepository {
    sender: mpsc::Sender<Command>,
}

impl CourseRepository {
    pub fn new(initial_data: Vec<Course>) -> Self {
        let (sender, mut receiver) = mpsc::channel(100);

        tokio::spawn(async move {
            let mut state = initial_data;
            while let Some(cmd) = receiver.recv().await {
                match cmd {
                    Command::Fetch { filter, respond_to } => {
                        let mut results: Vec<Course> = state
                            .iter()
                            .filter(|course| {
                                if let Some(ref ids) = filter.ids {
                                    if !ids.contains(&course.id) { return false; }
                                }
                                if let Some(pub_status) = filter.published {
                                    if course.published != pub_status { return false; }
                                }
                                if let Some(ref term) = filter.title {
                                    if !course.title.to_lowercase().contains(&term.to_lowercase()) { return false; }
                                }
                                if let Some(ref range) = filter.price_range {
                                    if let Some(min_val) = range.min { if course.price < min_val { return false; } }
                                    if let Some(max_val) = range.max { if course.price > max_val { return false; } }
                                }
                                true
                            })
                            .cloned()
                            .collect();

                        let strategy = filter.sort.unwrap_or(SortStrategy::IdAsc);
                        match strategy {
                            SortStrategy::IdAsc => results.sort_by(|a, b| a.id.cmp(&b.id)),
                            SortStrategy::IdDesc => results.sort_by(|a, b| b.id.cmp(&a.id)),
                            SortStrategy::TitleAsc => results.sort_by(|a, b| a.title.cmp(&b.title)),
                            SortStrategy::TitleDesc => results.sort_by(|a, b| b.title.cmp(&a.title)),
                            SortStrategy::PriceAsc => results.sort_by(|a, b| a.price.partial_cmp(&b.price).unwrap()),
                            SortStrategy::PriceDesc => results.sort_by(|a, b| b.price.partial_cmp(&a.price).unwrap()),
                        }
                        let _ = respond_to.send(results);
                    }
                    Command::Add { course, respond_to } => {
                        if state.iter().any(|c| c.id == course.id) {
                            let _ = respond_to.send(Err(format!("Validation Error: Course with ID {} already exists!", course.id)));
                            continue;
                        }
                        state.push(course);
                        let _ = respond_to.send(Ok(()));
                    }
                    Command::Update { id, updates, respond_to } => {
                        let index = state.iter().position(|c| c.id == id);
                        match index {
                            None => {
                                let _ = respond_to.send(Err(format!("Not Found Error: Course with ID {} does not exist!", id)));
                            }
                            Some(idx) => {
                                let current = &mut state[idx];
                                if let Some(t) = updates.title { current.title = t; }
                                if let Some(p) = updates.published { current.published = p; }
                                if let Some(pr) = updates.price { current.price = pr; }
                                let _ = respond_to.send(Ok(()));
                            }
                        }
                    }
                    Command::Delete { id, respond_to } => {
                        let len_before = state.len();
                        state.retain(|c| c.id != id);
                        if state.len() == len_before {
                            let _ = respond_to.send(Err(format!("Not Found Error: Course with ID {} does not exist!", id)));
                        } else {
                            let _ = respond_to.send(Ok(()));
                        }
                    }
                }
            }
        });

        Self { sender }
    }

    pub async fn fetch_courses(&self, filter: FilterCourses) -> Vec<Course> {
        let (tx, rx) = oneshot::channel();
        let _ = self.sender.send(Command::Fetch { filter, respond_to: tx }).await;
        rx.await.unwrap_or_default()
    }

    pub async fn add_course(&self, course: Course) -> Result<(), String> {
        let (tx, rx) = oneshot::channel();
        let _ = self.sender.send(Command::Add { course, respond_to: tx }).await;
        rx.await.unwrap_or_else(|_| Err("Actor dropped".to_string()))
    }

    pub async fn update_course(&self, id: i8, updates: UpdateCourse) -> Result<(), String> {
        let (tx, rx) = oneshot::channel();
        let _ = self.sender.send(Command::Update { id, updates, respond_to: tx }).await;
        rx.await.unwrap_or_else(|_| Err("Actor dropped".to_string()))
    }

    pub async fn delete_course(&self, id: i8) -> Result<(), String> {
        let (tx, rx) = oneshot::channel();
        let _ = self.sender.send(Command::Delete { id, respond_to: tx }).await;
        rx.await.unwrap_or_else(|_| Err("Actor dropped".to_string()))
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

    println!("\n--- Current Final Published State ---");
    let final_courses = repo.fetch_courses(FilterCourses::default_filter()).await;
    for c in final_courses {
        println!("{:?}", c);
    }
}