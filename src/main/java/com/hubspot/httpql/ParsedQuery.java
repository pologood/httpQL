package com.hubspot.httpql;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.apache.commons.lang.StringUtils;
import org.jooq.SortOrder;

import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.google.common.base.Strings;
import com.hubspot.httpql.ann.FilterBy;
import com.hubspot.httpql.error.UnknownFieldException;
import com.hubspot.httpql.impl.Ordering;
import com.hubspot.httpql.internal.BoundFilterEntry;
import com.hubspot.httpql.internal.FilterEntry;
import com.hubspot.httpql.internal.MultiValuedBoundFilterEntry;

/**
 * The result of parsing query arguments.
 *
 * @author tdavis
 */
public class ParsedQuery<T extends QuerySpec> {

  private final Class<T> queryType;

  private final List<BoundFilterEntry<T>> boundFilterEntries;
  private final T boundQuerySpec;

  private final MetaQuerySpec<T> meta;

  private Collection<Ordering> orderings;

  private Optional<Integer> limit;
  private Optional<Integer> offset;

  private boolean includeDeleted;

  public ParsedQuery(
      T boundQuerySpec,
      Class<T> queryType,
      List<BoundFilterEntry<T>> boundFilterEntries,
      MetaQuerySpec<T> meta,
      Optional<Integer> limit,
      Optional<Integer> offset,
      Collection<Ordering> orderings,
      boolean includeDeleted) {

    this.boundQuerySpec = boundQuerySpec;
    this.queryType = queryType;
    this.boundFilterEntries = boundFilterEntries;
    this.meta = meta;

    this.limit = limit;
    this.offset = offset;
    this.orderings = orderings;

    this.includeDeleted = includeDeleted;
  }

  /**
   * Check to see if any filter exists for a given field.
   *
   * @param fieldName
   *          Name as seen in the query; not multi-value proxies ("id", not "ids")
   */
  public boolean hasFilter(String fieldName) {
    for (BoundFilterEntry<T> bfe : getBoundFilterEntries()) {
      if (bfe.getQueryName().equals(fieldName)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Check to see a specific filter type exists for a given field.
   *
   * @param fieldName
   *          Name as seen in the query; not multi-value proxies ("id", not "ids")
   */
  public boolean hasFilter(String fieldName, Class<? extends Filter> filterType) {
    for (BoundFilterEntry<T> bfe : getBoundFilterEntries()) {
      if (bfe.getQueryName().equals(fieldName) && bfe.getFilter().getClass().equals(filterType)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Add the given filter to the query.
   *
   * @param fieldName
   *          Name as seen in the query; not multi-value proxies ("id", not "ids")
   * @throws UnknownFieldException
   *           When no field named {@code fieldName} exists
   * @throws IllegalArgumentException
   *           When {@code value} is of the wrong type
   */
  public void addFilter(String fieldName, Class<? extends Filter> filterType, Object value) {
    // Filter can be null; we only want FilterEntry for name normalization
    Filter filter = DefaultMetaUtils.getFilterInstance(filterType);
    FilterEntry filterEntry = new FilterEntry(filter, fieldName, this.getQueryType());
    BeanPropertyDefinition filterProperty = getMetaData().getFilterTable().get(filterEntry, filter.names()[0]);
    if (filterProperty == null) {
      throw new UnknownFieldException(String.format("No filter %s on field named '%s' exists.", filter.names()[0], fieldName));
    }
    BoundFilterEntry<T> boundColumn = new BoundFilterEntry<T>(filterEntry, filterProperty, getMetaData());
    FilterBy ann = filterProperty.getPrimaryMember().getAnnotation(FilterBy.class);
    if (Strings.emptyToNull(ann.as()) != null) {
      boundColumn.setActualField(getMetaData().getFieldMap().get(ann.as()));
    }

    if (boundColumn.isMultiValue()) {
      Collection<?> values = (Collection<?>) value;
      boundColumn = new MultiValuedBoundFilterEntry<T>(boundColumn, values);
    } else {
      filterProperty.getSetter().setValue(getBoundQuery(), value);
    }

    if (!getBoundFilterEntries().contains(boundColumn)) {
      getBoundFilterEntries().add(boundColumn);
    }
  }

  /**
   * Add the given order-by clause. The operation is not checked against allowed order-bys.
   *
   * @param fieldName
   *          Name as seen in the query; not multi-value proxies ("id", not "ids")
   */
  public void addOrdering(String fieldName, SortOrder order) {
    FilterEntry entry = new FilterEntry(null, fieldName, getQueryType());
    BeanPropertyDefinition prop = getMetaData().getFieldMap().get(entry.getQueryName());
    if (prop == null) {
      prop = getMetaData().getFieldMap().get(entry.getFieldName());
    }
    if (prop != null) {
      orderings.add(new Ordering(entry.getFieldName(), entry.getQueryName(), order));
    }
  }

  /**
   * Similar to {@link #addFilter} but removes all existing filters for {@code fieldName} first.
   *
   * @param fieldName
   *          Name as seen in the query; not multi-value proxies ("id", not "ids")
   */
  public void addFilterExclusively(String fieldName, Class<? extends Filter> filterType, Object value) {
    removeFiltersFor(fieldName);
    addFilter(fieldName, filterType, value);
  }

  public boolean removeFiltersFor(String fieldName) {
    boolean removed = false;
    Iterator<BoundFilterEntry<T>> iterator = getBoundFilterEntries().iterator();
    while (iterator.hasNext()) {
      BoundFilterEntry<T> bfe = iterator.next();
      if (bfe.getQueryName().equals(fieldName)) {
        iterator.remove();
        removed = true;
      }
    }
    return removed;
  }

  public BoundFilterEntry<T> getFirstFilterForFieldName(String fieldName) {
    Iterator<BoundFilterEntry<T>> iterator = getBoundFilterEntries().iterator();
    while (iterator.hasNext()) {
      BoundFilterEntry<T> bfe = iterator.next();
      if (bfe.getQueryName().equals(fieldName)) {
        iterator.remove();
        return bfe;
      }
    }
    return null;
  }

  public void removeAllFilters() {
    getBoundFilterEntries().clear();
  }

  public String getCacheKey() {
    List<Object> cacheKeyParts = new ArrayList<>();

    for (BoundFilterEntry<T> bfe : boundFilterEntries) {
      cacheKeyParts.add(bfe.getFieldName());
      cacheKeyParts.add(bfe.getFilter().getClass().getSimpleName());

      if (bfe instanceof MultiValuedBoundFilterEntry) {
        cacheKeyParts.addAll(((MultiValuedBoundFilterEntry<T>) bfe).getValues());
      } else {
        cacheKeyParts.add(Objects.toString(bfe.getProperty().getGetter().getValue(boundQuerySpec)));
      }
    }

    for (Ordering o : orderings) {
      cacheKeyParts.add(o.getFieldName());
      cacheKeyParts.add(o.getOrder().ordinal());
    }

    cacheKeyParts.add(offset.orElse(0));
    cacheKeyParts.add(limit.orElse(0));

    return StringUtils.join(cacheKeyParts, ":");
  }

  public void setLimit(Optional<Integer> limit) {
    this.limit = limit;
  }

  public void setOffset(Optional<Integer> offset) {
    this.offset = offset;
  }

  public void setOrderings(Collection<Ordering> orderings) {
    this.orderings = orderings;
  }

  public Optional<Integer> getLimit() {
    return limit;
  }

  public Optional<Integer> getOffset() {
    return offset;
  }

  public Collection<Ordering> getOrderings() {
    return orderings;
  }

  public boolean getIncludeDeleted() {
    return includeDeleted;
  }

  public T getBoundQuery() {
    return boundQuerySpec;
  }

  public List<BoundFilterEntry<T>> getBoundFilterEntries() {
    return boundFilterEntries;
  }

  public Class<T> getQueryType() {
    return queryType;
  }

  protected MetaQuerySpec<T> getMetaData() {
    return meta;
  }

  @Override
  public ParsedQuery<T> clone() {
    return new ParsedQuery<>(
        getBoundQuery(),
        getQueryType(),
        new ArrayList<>(getBoundFilterEntries()),
        getMetaData(),
        getLimit(),
        getOffset(),
        new ArrayList<>(getOrderings()),
        getIncludeDeleted());
  }

}
