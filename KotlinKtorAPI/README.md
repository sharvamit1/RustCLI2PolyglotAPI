# Endpoints

1. http://127.0.0.1:8080/api/v1/courses
   
2. http://127.0.0.1:8080/api/v1/courses?published=YES&sort=PRICE_DESC
   
3. http://127.0.0.1:8080/api/v1/courses?ids=1,3,5,6&minPrice=50.0

4. Create Course
   curl -X POST "http://127.0.0.1:8080/api/v1/courses" -H "Content-Type: application/json" -d "{\"id\":7,\"title\":\"Title 7\",\"published\":\"YES\",\"price\":50.0}

5. Update Course
   curl -X PUT "http://127.0.0.1:8080/api/v1/courses/7" -H "Content-Type: application/json" -d "{\"title\":\"Product 7\",\"price\":59.99}

6. Delete Course
   curl -X DELETE "http://127.0.0.1:8080/api/v1/courses/7"
