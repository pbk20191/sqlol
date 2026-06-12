package org.example.demo1webfl.repository

import org.example.demo1webfl.models.Book
import org.springframework.data.jpa.repository.JpaRepository

interface BookJpaRepository: JpaRepository<Book, Long> {
}