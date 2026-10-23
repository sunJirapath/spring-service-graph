package demo; class Call { RestTemplate restTemplate; void call() { restTemplate.getForObject("http://target?x=1", Object.class); } }
