package com.skydex.api.repositories

import com.skydex.api.models.UploadedPhoto
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Repository
interface UploadedPhotoRepository : JpaRepository<UploadedPhoto, UUID> {

    fun findByFilename(filename: String): UploadedPhoto?

    /**
     * Removes every photo row [uploaderId] uploaded. Used only by account deletion.
     *
     * Rows only: the JPEGs themselves stay on disk. There is no delete-photo endpoint and no
     * sweep yet — that is the orphaned-file backlog item — so this deliberately leaves the bytes
     * and takes the rows, which are the part that would otherwise reference a user id that no
     * longer resolves.
     */
    fun deleteByUploaderId(uploaderId: UUID)

    /**
     * Spends the photo, atomically. Returns the number of rows the database actually changed: `1`
     * if this caller won it, `0` if the photo was already spent.
     *
     * The `consumed_at is null` predicate is the entire single-use guarantee, and it has to live
     * in the WHERE clause rather than in Kotlin. Read-then-write cannot hold it: two concurrent
     * captures citing the same filename both read a null `consumedAt`, both decide they may
     * proceed, and both write — one real photo becomes N captures and N times the XP. The unique
     * constraint on `filename` does not help, because the second operation is an UPDATE of a row
     * that already exists, not an INSERT. Here the database serialises the two updates on the row
     * lock and the loser is told, by the row count, that it lost.
     *
     * Deliberately NOT `@Version`/optimistic locking: that surfaces as
     * `OptimisticLockingFailureException`, i.e. a 500, where the contract calls for a 400 carrying
     * a specific message. A row count is a value this code can turn into the right error.
     *
     * `@Transactional` here so the write is self-contained when called directly; called from
     * within `CaptureCommitService.commit` it simply joins that transaction (REQUIRED) and commits
     * with the capture.
     */
    @Modifying
    @Transactional
    @Query("update UploadedPhoto p set p.consumedAt = :now where p.id = :id and p.consumedAt is null")
    fun consume(@Param("id") id: UUID, @Param("now") now: Instant): Int
}
