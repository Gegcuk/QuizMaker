package uk.gegc.quizmaker.features.document.domain.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "document_chunks")
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class DocumentChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    @Column(nullable = false)
    private Integer chunkIndex;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String title;

    @Column(columnDefinition = "MEDIUMTEXT", nullable = false)
    private String content;

    @Column(nullable = false)
    private Integer startPage;

    @Column(nullable = false)
    private Integer endPage;

    @Column(nullable = false)
    private Integer wordCount;

    @Column(nullable = false)
    private Integer characterCount;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column
    private String chapterTitle;

    @Column
    private String sectionTitle;

    @Column
    private Integer chapterNumber;

    @Column
    private Integer sectionNumber;

    @Column
    private ChunkType chunkType;

    public enum ChunkType {
        CHAPTER,
        SECTION,
        PAGE_BASED,
        SIZE_BASED
    }
} 