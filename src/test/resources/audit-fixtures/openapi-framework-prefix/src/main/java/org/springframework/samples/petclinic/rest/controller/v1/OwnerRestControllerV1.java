package org.springframework.samples.petclinic.rest.controller.v1;
import org.springframework.samples.petclinic.rest.api.OwnersApi;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
@RestController
@RequestMapping("/api")
public class OwnerRestControllerV1 implements OwnersApi {
  public Object listOwners(String lastName) { return null; }
}
