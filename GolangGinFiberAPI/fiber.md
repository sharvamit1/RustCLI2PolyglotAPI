Run project

	go run fiber.go

Endpoints
	
	1. http://127.0.0.1:3000/api/v1/courses

	2. http://127.0.0.1:3000/api/v1/courses?published=yes&sort=price_asc

	3. http://127.0.0.1:3000/api/v1/courses?ids=1,3,5,6&min_price=50.0

	4. curl -X POST "http://127.0.0.1:3001/api/v1/courses" -H "Content-Type: application/json" -d "{\"id\":7,\"title\":\"Title 7\",\"published\":\"YES\",\"price\":50.0}"

	5. curl -X PATCH "http://127.0.0.1:3001/api/v1/courses/7" -H "Content-Type: application/json" -d "{\"title\":\"Product 7\",\"price\":59.99}"

	6. curl -X DELETE http://127.0.0.1:3001/api/v1/courses/7