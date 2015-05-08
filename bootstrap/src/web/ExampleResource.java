package {{basePackage}}.web;

import static java.util.Objects.requireNonNull;

import javax.inject.Inject;
import javax.inject.Singleton;

import com.mainlinedelivery.service.RestaurantService;
import com.soklet.web.annotation.GET;
import com.soklet.web.annotation.Resource;
import com.soklet.web.response.PageResponse;

/**
 * @author {{authorName}}
 */
@Resource
@Singleton
public class ExampleResource {
  private final ExampleService exampleService;

  @Inject
  public ExampleResource(ExampleService exampleService) {
    this.exampleService = requireNonNull(exampleService);
  }
  
  @GET("/")
  public PageResponse indexPage() {
    return new PageResponse("index");
  }
  
  @GET("/examples")
  public PageResponse examplesPage() {
    return new PageResponse("example");
  }  
  
  @GET("/examples/{exampleId}")
  public PageResponse examplePage() {
    return new PageResponse("example");
  }
  
  @POST("/api/examples")
  public ApiResponse addExample(@RequestBody String requestBody) {
    ExampleCommand exampleCommand = 
    exampleService.addExample(command);
    return new ApiResponse("example");
  }    
}