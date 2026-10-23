package client; @FeignClient(name = "users") interface UsersClient { @GetMapping("/users/{id}") String get(@PathVariable String id); }
