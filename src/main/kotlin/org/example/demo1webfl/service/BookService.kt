package org.example.demo1webfl.service

import org.example.demo1webfl.repository.BookJpaRepository
import org.springframework.stereotype.Service

@Service
class BookService(
    val repository: BookJpaRepository
) {


    fun asdf() {
        repository.findById(0)
    }
}