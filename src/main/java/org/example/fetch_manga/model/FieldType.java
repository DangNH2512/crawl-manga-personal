package org.example.fetch_manga.model;

/**
 * Kiểu của một ô nhập liệu trên form. View dựa vào đây để dựng đúng component
 * Swing tương ứng, nhờ vậy View không cần biết gì về từng nguồn truyện cụ thể.
 */
public enum FieldType {

    /** Ô nhập một dòng. */
    TEXT,

    /** Ô nhập nhiều dòng (cookie dài, lệnh cURL...). */
    TEXTAREA,

    /** Danh sách chọn cố định (xem {@link FieldSpec#getOptions()}). */
    CHOICE
}
