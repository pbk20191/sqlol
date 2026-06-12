package org.example.sqlol.service

import org.example.sqlol.repository.BookJpaRepository
import org.springframework.stereotype.Service

@Service
class BookService(
    val repository: BookJpaRepository
) {


    fun asdf() {
        repository.findById(0)
    }
}