package org.example.sqlol.repository

import org.example.sqlol.models.Book
import org.springframework.data.jpa.repository.JpaRepository

interface BookJpaRepository: JpaRepository<Book, Long> {
}