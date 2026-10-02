package com.chy.mall.control;


import com.chy.mall.common.ApiResponse;
import com.chy.mall.dto.CreateProductRequest;
import com.chy.mall.dto.ProductPageQuery;
import com.chy.mall.service.ProductService;
import com.chy.mall.vo.PageVo;
import com.chy.mall.vo.ProductVo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/products")
@RequiredArgsConstructor
public class ProductController {
    private final ProductService productService;
    @PostMapping("/create")
    public ResponseEntity<ApiResponse<ProductVo>> create(@Valid @RequestBody CreateProductRequest request){
        ProductVo productVo = productService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(productVo));
    }

    // 例如GET /products/1：@PathVariable从路径读取ID，不需要请求体。
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ProductVo>> getById(@PathVariable @Positive(message = "商品ID必须大于0") Long id) {
        ProductVo productVo = productService.getById(id);
        return ResponseEntity.ok(ApiResponse.success(productVo));
    }

    // GET /products/page?page=1&size=10&keyword=Java&status=ON_SALE
    // 从URL查询参数绑定DTO并校验；/page静态路径不会被/{id}当作商品ID。
    @GetMapping("/page")
    public ResponseEntity<ApiResponse<PageVo<ProductVo>>> page(@Valid @ModelAttribute ProductPageQuery query) {
        return ResponseEntity.ok(ApiResponse.success(productService.page(query)));
    }
}
