package com.gpumarket.domain.catalog.service;

import com.gpumarket.domain.catalog.dto.CatalogResponseDto;
import com.gpumarket.domain.catalog.repository.CatalogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogService {
  private final CatalogRepository repository;

  public CatalogService(CatalogRepository repository) {
    this.repository = repository;
  }

  @Transactional(readOnly = true)
  public CatalogResponseDto getCatalog() {
    return repository.findCatalog();
  }
}
