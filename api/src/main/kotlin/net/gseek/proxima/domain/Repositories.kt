package net.gseek.proxima.domain

import org.springframework.data.jpa.repository.JpaRepository

interface LearnerRepository : JpaRepository<Learner, Long>

interface ConceptRepository : JpaRepository<Concept, Long>

interface ItemRepository : JpaRepository<Item, Long>

interface AttemptRepository : JpaRepository<Attempt, Long>

interface MasteryRepository : JpaRepository<Mastery, Long> {

    fun findByLearnerIdAndConceptId(learnerId: Long, conceptId: Long): Mastery?
}
