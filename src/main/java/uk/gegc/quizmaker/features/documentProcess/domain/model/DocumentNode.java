package uk.gegc.quizmaker.features.documentProcess.domain.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "document_nodes")
@Getter
@Setter
public class DocumentNode {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    private NormalizedDocument document;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private DocumentNode parent;

    @OneToMany(mappedBy = "parent", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("idx ASC")
    @org.hibernate.annotations.BatchSize(size = 256)
    private List<DocumentNode> children = new ArrayList<>();

    @Column(name = "idx", nullable = false)
    private Integer idx;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private NodeType type;

    @Column(name = "title", length = 512)
    private String title;

    @Column(name = "start_offset", nullable = false)
    private Integer startOffset;

    @Column(name = "end_offset", nullable = false)
    private Integer endOffset;

    @Column(name = "start_anchor", columnDefinition = "TEXT")
    private String startAnchor;

    @Column(name = "end_anchor", columnDefinition = "TEXT")
    private String endAnchor;

    @Column(name = "depth", nullable = false)
    private Short depth;

    @Column(name = "ai_confidence", precision = 4, scale = 3)
    private BigDecimal aiConfidence;

    @Column(name = "meta_json", columnDefinition = "JSON")
    private String metaJson;

    public enum NodeType {
        PART, BOOK, CHAPTER, SECTION, SUBSECTION, PARAGRAPH, UTTERANCE, OTHER
    }

    // Helper methods for bidirectional relationship
    public void addChild(DocumentNode child) {
        children.add(child);
        child.setParent(this);
    }

    public void removeChild(DocumentNode child) {
        children.remove(child);
        child.setParent(null);
    }
}
