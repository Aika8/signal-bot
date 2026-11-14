package io.github.signalbot.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;

/** Stores the nightly written review (one row per day). */
@Repository
public class DailyReviewStore {

    private final JdbcClient db;

    public DailyReviewStore(JdbcClient db) {
        this.db = db;
    }

    public void upsert(LocalDate date, String summary, String reviewJson) {
        db.sql("""
                        INSERT INTO daily_reviews (review_date, summary, review)
                        VALUES (:date, :summary, CAST(:review AS jsonb))
                        ON CONFLICT (review_date) DO UPDATE SET
                            summary = EXCLUDED.summary,
                            review = EXCLUDED.review
                        """)
                .param("date", java.sql.Date.valueOf(date))
                .param("summary", summary)
                .param("review", reviewJson)
                .update();
    }
}
