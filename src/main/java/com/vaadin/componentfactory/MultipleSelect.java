package com.vaadin.componentfactory;

import com.vaadin.flow.component.*;
import com.vaadin.flow.component.dependency.JsModule;
import com.vaadin.flow.data.binder.HasDataProvider;
import com.vaadin.flow.data.binder.HasItemsAndComponents;
import com.vaadin.flow.data.provider.DataChangeEvent;
import com.vaadin.flow.data.provider.DataProvider;
import com.vaadin.flow.data.provider.KeyMapper;
import com.vaadin.flow.data.provider.Query;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.data.renderer.TextRenderer;
import com.vaadin.flow.data.selection.MultiSelect;
import com.vaadin.flow.data.selection.MultiSelectionEvent;
import com.vaadin.flow.data.selection.MultiSelectionListener;
import com.vaadin.flow.dom.PropertyChangeEvent;
import com.vaadin.flow.dom.PropertyChangeListener;
import com.vaadin.flow.function.SerializableConsumer;
import com.vaadin.flow.function.SerializablePredicate;
import com.vaadin.flow.shared.Registration;
import elemental.json.Json;
import elemental.json.JsonArray;

import java.io.Serializable;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

@JsModule("./multipleSelectConnector.js")
public class MultipleSelect<T> extends MultipleSelectBase<MultipleSelect<T>, T, Set<T>>
    implements MultiSelect<MultipleSelect<T>, T>,
        HasDataProvider<T>,
        HasItemsAndComponents<T>,
        HasSize,
        HasValidation,
        HasHelper {

  public static final String LABEL_ATTRIBUTE = "label";
  private List<T> items;

  /** Constructs a multiple-select. */
  public MultipleSelect() {
    super(
        "selectedIndexes",
        Collections.emptySet(),
        JsonArray.class,
        MultipleSelect::presentationToModel,
        MultipleSelect::modelToPresentation);

    getElement().setProperty("invalid", false);
    getElement().setProperty("opened", false);

    getElement().appendChild(listBox.getElement());

    registerValidation();
  }

  private static <T> Set<T> presentationToModel(MultipleSelect<T> select, JsonArray presentation) {
    Set<T> modelValue =
        IntStream.range(0, presentation.length())
            .map(i -> (int) presentation.getNumber(i))
            .filter(i -> i < select.items.size())
            .mapToObj(index -> select.items.get(index))
            .collect(Collectors.toSet());
    return Collections.unmodifiableSet(modelValue);
  }

  private static <T> JsonArray modelToPresentation(MultipleSelect<T> select, Set<T> model) {
    JsonArray array = Json.createArray();
    model.stream().map(select.items::indexOf).forEach(index -> array.set(array.length(), index));
    return array;
  }

  private final KeyMapper<T> keyMapper = new KeyMapper<>();

  /*
   * Internal version of list box that is just used to delegate the child
   * components to. vaadin-select.html imports vaadin-list-box.html.
   *
   * Using this internally allows all events and updates to the children
   * (items, possible child components) to work even though the list box
   * element is moved on the client side in the renderer method from light-dom
   * to be a child of the select overlay.
   *
   * Not using the proper ListBox because all communication & updates are
   * going through the Select. Using ListBox would just duplicate things, and
   * cause e.g. unnecessary synchronizations and dependency to the Java
   * integration.
   *
   * The known side effect is that at the element level, the child components
   * are not the correct ones, e.g. the list box is the only child of select,
   * even though that is not visible from the component level.
   */
  @Tag("vaadin-list-box")
  private class InternalListBox extends Component implements HasItemsAndComponents<T> {

    @Override
    public void setItems(Collection<T> collection) {
      // NOOP, never used directly, just need to have it here
      throw new UnsupportedOperationException(
          "The setItems method of the internal ListBox of the MultipleSelect component should never be called.");
    }

    @Override
    public int getItemPosition(T item) {
      // null item is the empty selection item and that is always first
      if ((item == null) && isEmptySelectionAllowed()) {
        return 0;
      } else {
        return HasItemsAndComponents.super.getItemPosition(item);
      }
    }
  }

  private final InternalListBox listBox = new InternalListBox();

  private DataProvider<T, ?> dataProvider = DataProvider.ofItems();

  private ComponentRenderer<? extends Component, T> itemRenderer;

  private SerializablePredicate<T> itemEnabledProvider = null;

  private ItemLabelGenerator<T> itemLabelGenerator = null;

  private final PropertyChangeListener validationListener = this::validateSelectionEnabledState;
  private Registration validationRegistration;
  private Registration dataProviderListenerRegistration;
  private boolean resetPending = true;

  private boolean emptySelectionAllowed;

  private String emptySelectionCaption;

  private VaadinItem<T> emptySelectionItem;
  private String singularString;
  private String pluralString;

  /**
   * Constructs a select with the given items.
   *
   * @param items the items for the select
   */
  public MultipleSelect(T... items) {
    this();
    setItems(items);
  }

  @Override
  public void setItems(Collection<T> items) {
    this.items = Collections.unmodifiableList(new ArrayList<>(items));
    HasDataProvider.super.setItems(items);
  }

  /**
   * Returns the item component renderer.
   *
   * @return the item renderer or {@code null} if none set
   * @see #setRenderer(ComponentRenderer)
   */
  public ComponentRenderer<? extends Component, T> getItemRenderer() {
    return itemRenderer;
  }

  /**
   * Sets the item renderer for this select group. The renderer is applied to each item to create a
   * component which represents the item option in the select's drop down.
   *
   * <p>Default is {@code null} which means that the item's {@link #toString()} method is used and
   * set as the text content of the vaadin item element.
   *
   * @param renderer the item renderer, or {@code null} to clear
   */
  public void setRenderer(ComponentRenderer<? extends Component, T> renderer) {
    this.itemRenderer = renderer;
    refreshItems();
  }

  /**
   * Convenience setter for creating a {@link TextRenderer} from the given function that converts
   * the item to a string.
   *
   * <p><em>NOTE:</em> even though this accepts an {@link ItemLabelGenerator}, this is not the same
   * as {@link #setItemLabelGenerator(ItemLabelGenerator)} which does a different thing.
   *
   * @param itemLabelGenerator the function that creates the text content from the item, not {@code
   *     null}
   */
  public void setTextRenderer(ItemLabelGenerator<T> itemLabelGenerator) {
    Objects.requireNonNull(itemLabelGenerator);
    setRenderer(new TextRenderer<>(itemLabelGenerator));
  }

  /**
   * Sets whether the user is allowed to select nothing. When set {@code true} a special empty item
   * is shown to the user.
   *
   * <p>Default is {@code false}. The empty selection item can be customized with {@link
   * #setEmptySelectionCaption(String)}.
   *
   * @param emptySelectionAllowed {@code true} to allow not selecting anything, {@code false} to
   *     require selection
   * @see #setEmptySelectionCaption(String)
   */
  public void setEmptySelectionAllowed(boolean emptySelectionAllowed) {
    if (isEmptySelectionAllowed() == emptySelectionAllowed) {
      return;
    }
    if (isEmptySelectionAllowed()) {
      removeEmptySelectionItem();
    } else {
      addEmptySelectionItem();
    }
    this.emptySelectionAllowed = emptySelectionAllowed;
  }

  /**
   * Returns whether the user is allowed to select nothing.
   *
   * @return {@code true} if empty selection is allowed, {@code false} otherwise
   */
  public boolean isEmptySelectionAllowed() {
    return emptySelectionAllowed;
  }

  /**
   * Sets the empty selection caption when {@link #setEmptySelectionAllowed(boolean)} has been
   * enabled. The caption is shown for the empty selection item in the drop down.
   *
   * <p>When the empty selection item is selected, the select shows the value provided by {@link
   * #setItemLabelGenerator(ItemLabelGenerator)} for the {@code null} item, or the string set with
   * {@link #setPlaceholder(String)} or an empty string if not placeholder is set.
   *
   * <p>Default is an empty string "", which will show the place holder when selected.
   *
   * @param emptySelectionCaption the empty selection caption to set, not {@code null}
   * @see #setEmptySelectionAllowed(boolean)
   */
  public void setEmptySelectionCaption(String emptySelectionCaption) {
    Objects.requireNonNull(emptySelectionCaption, "Empty selection caption must not be null");

    this.emptySelectionCaption = emptySelectionCaption;

    if (emptySelectionItem != null) {
      updateItem(emptySelectionItem);
    }
  }

  public String getEmptySelectionCaption() {
    return emptySelectionCaption == null ? "" : emptySelectionCaption;
  }

  /**
   * Returns the item enabled predicate.
   *
   * @return the item enabled predicate or {@code null} if not set
   * @see #setItemEnabledProvider
   */
  public SerializablePredicate<T> getItemEnabledProvider() {
    return itemEnabledProvider;
  }

  /**
   * Sets the item enabled predicate for this select. The predicate is applied to each item to
   * determine whether the item should be enabled ({@code true}) or disabled ({@code false}).
   * Disabled items are displayed as grayed out and the user cannot select them.
   *
   * <p>By default is {@code null} and all the items are enabled.
   *
   * @param itemEnabledProvider the item enable predicate or {@code null} to clear
   */
  public void setItemEnabledProvider(SerializablePredicate<T> itemEnabledProvider) {
    this.itemEnabledProvider = itemEnabledProvider;
    refreshItems();
  }

  /**
   * Gets the item label generator. It generates the text that is shown in the input part for the
   * item when it has been selected.
   *
   * <p>Default is {@code null}.
   *
   * @return the item label generator, {@code null} if not set
   */
  public ItemLabelGenerator<T> getItemLabelGenerator() {
    return itemLabelGenerator;
  }

  /**
   * Sets the item label generator. It generates the text that is shown in the input part for the
   * item when it has been selected.
   *
   * <p>Default is {@code null} and the text content generated for the item with {@link
   * #setRenderer(ComponentRenderer)} is used instead.
   *
   * @param itemLabelGenerator the item label generator to set, or {@code null} to clear
   */
  public void setItemLabelGenerator(ItemLabelGenerator<T> itemLabelGenerator) {
    this.itemLabelGenerator = itemLabelGenerator;
    refreshItems();
  }

  /**
   * Gets the placeholder hint set for the user.
   *
   * @return the placeholder or {@code null} if none set
   */
  public String getPlaceholder() {
    return super.getPlaceholderString();
  }

  /**
   * Sets the placeholder hint for the user.
   *
   * <p>The placeholder will be displayed in the case that there is no item selected, or the
   * selected item has an empty string label, or the selected item has no label and it's DOM content
   * is empty.
   *
   * <p>Default value is {@code null}.
   *
   * @param placeholder the placeholder to set, or {@code null} to remove
   */
  @Override
  public void setPlaceholder(String placeholder) {
    super.setPlaceholder(placeholder);
  }

  /**
   * When true, it indicates that all selected items will be shown comma-separated (with ellipsis if
   * more items are present than fits the component).
   *
   * <p>If false, it indicates that after the first selected item, additional selected items will be
   * abbreviated (showing only the number of additionally selected items between brackets).
   *
   * <p>This property is not synchronized automatically from the client side, so the returned value
   * may not be the same as in client side.
   *
   * @return the {@code displayAllSelected} property from the webcomponent
   */
  @Override
  public boolean isDisplayAllSelected() {
    return super.isDisplayAllSelected();
  }

  /**
   * If set to true, all selected items will be shown comma-separated (with ellipsis if more items
   * are present than fits the component).
   *
   * <p>When set to false (default), it indicates that after the first selected item, additional
   * selected items will be abbreviated (showing only the number of additionally selected items
   * between brackets).
   *
   * @param displayAllSelected the boolean value to set
   * @see #setExtraItemsCountText(String singularString, String pluralString)
   */
  @Override
  public void setDisplayAllSelected(boolean displayAllSelected) {
    super.setDisplayAllSelected(displayAllSelected);
  }

  /**
   * Gets the texts shown after the count when more than a single item is selected.
   *
   * @return an array of two Strings: the first of which represents the text shown when only on
   *     extra item is selected. The second represents the text shown when two or more extra items
   *     selected.
   */
  public String[] getExtraItemsCountText() {
    return new String[] {singularString, pluralString};
  }

  /**
   * Sets the text shown after the count when more than a single item is selected.
   *
   * <p>Note that setting the text will have any effect only if the component's {@code
   * display-all-selected} attribute is present (which can be toggled using {@code
   * setDisplayAllSelected(true/false)}).
   *
   * @param singularString the text shown when only on extra item is selected.
   * @param pluralString the text shown when two or more extra items selected.
   * @see #setDisplayAllSelected(boolean displayAllSelected)
   */
  @Override
  public void setExtraItemsCountText(String singularString, String pluralString) {
    super.setExtraItemsCountText(singularString, pluralString);
    this.singularString = singularString;
    this.pluralString = pluralString;
  }

  /**
   * Sets the string for the label element.
   *
   * <p><em>NOTE:</em> the label must be set for the required indicator to be visible.
   *
   * @param label string or {@code null} to clear it
   */
  @Override
  public void setLabel(String label) {
    super.setLabel(label);
  }

  /**
   * Gets the string for the label element.
   *
   * @return the label string, or {@code null} if not set
   */
  public String getLabel() {
    return super.getLabelString();
  }

  /**
   * Sets the select to have focus when the page loads.
   *
   * <p>Default is {@code false}.
   *
   * @param autofocus the autofocus to set
   */
  @Override
  public void setAutofocus(boolean autofocus) {
    super.setAutofocus(autofocus);
  }

  /**
   * Gets whether this select has been set to autofocus when the page loads.
   *
   * @return {@code true} if set to autofocus, {@code false} if not
   */
  public boolean isAutofocus() {
    return super.isAutofocusBoolean();
  }

  @Override
  public void setDataProvider(DataProvider<T, ?> dataProvider) {
    this.dataProvider = dataProvider;
    reset();

    if (dataProviderListenerRegistration != null) {
      dataProviderListenerRegistration.remove();
    }
    dataProviderListenerRegistration = dataProvider.addDataProviderListener(this::onDataChange);
  }

  /**
   * Gets the data provider.
   *
   * @return the data provider, not {@code null}
   */
  public DataProvider<T, ?> getDataProvider() {
    return dataProvider;
  }

  @Override
  public void onEnabledStateChanged(boolean enabled) {
    setDisabled(!enabled);
    getItems().forEach(this::updateItemEnabled);
  }

  /**
   * {@inheritDoc}
   *
   * <p><em>NOTE:</em> The required indicator will not be visible, if the {@link #setLabel(String)}
   * property is not set for the select.
   */
  @Override
  public void setRequiredIndicatorVisible(boolean requiredIndicatorVisible) {
    // this would be the same as setRequired(boolean) but we don't expose
    // both
    super.setRequiredIndicatorVisible(requiredIndicatorVisible);
  }

  /**
   * {@inheritDoc}
   *
   * <p><em>NOTE:</em> The required indicator will not be visible, if the {@link #setLabel(String)}
   * property is not set for the select.
   */
  @Override
  public boolean isRequiredIndicatorVisible() {
    return super.isRequiredBoolean();
  }

  /**
   * Sets the error message to show to the user on invalid selection.
   *
   * @param errorMessage the error message or {@code null} to clear it
   */
  @Override
  public void setErrorMessage(String errorMessage) {
    super.setErrorMessage(errorMessage);
  }

  /**
   * Gets the error message to show to the user on invalid selection
   *
   * @return the error message or {@code null} if not set
   */
  @Override
  public String getErrorMessage() {
    return super.getErrorMessageString();
  }

  /**
   * Sets the multiple-select to show as invalid state and display error message.
   *
   * @param invalid {@code true} for invalid, {@code false} for valid
   */
  @Override
  public void setInvalid(boolean invalid) {
    super.setInvalid(invalid);
  }

  /**
   * Gets whether the multiple-select is currently in invalid state.
   *
   * @return {@code true} for invalid, {@code false} for valid
   */
  @Override
  public boolean isInvalid() {
    return super.isInvalidBoolean();
  }

  /**
   * {@inheritDoc}
   *
   * <p><em>NOTE:</em> If you add a component with the {@code slot} attribute set, it will be placed
   * in the light-dom of the {@code vcf-multi-select} instead of the drop down, similar to {@link
   * #addToPrefix(Component...)}
   */
  @Override
  public void add(Component... components) {
    Objects.requireNonNull(components, "Components should not be null");
    for (Component component : components) {
      if (component.getElement().hasAttribute("slot")) {
        HasItemsAndComponents.super.add(component);
      } else {
        listBox.add(component);
      }
    }
  }

  @Override
  public void addComponents(T afterItem, Component... components) {
    listBox.addComponents(afterItem, components);
  }

  @Override
  public void prependComponents(T beforeItem, Component... components) {
    listBox.prependComponents(beforeItem, components);
  }

  /**
   * {@inheritDoc}
   *
   * <p><em>NOTE:</em> If you add a component with the {@code slot} attribute set, it will be placed
   * in the light-dom of the {@code vcf-multi-select} instead of the drop down, similar to {@link
   * #addToPrefix(Component...)}
   */
  @Override
  public void addComponentAtIndex(int index, Component component) {
    Objects.requireNonNull(component, "Component should not be null");
    if (component.getElement().hasAttribute("slot")) {
      HasItemsAndComponents.super.addComponentAtIndex(index, component);
    } else {
      listBox.addComponentAtIndex(index, component);
    }
  }

  /**
   * {@inheritDoc}
   *
   * <p><em>NOTE:</em> If you add a component with the {@code slot} attribute set, it will be placed
   * in the light-dom of the {@code vcf-multi-select} instead of the drop down, similar to {@link
   * #addToPrefix(Component...)}
   */
  @Override
  public void addComponentAsFirst(Component component) {
    Objects.requireNonNull(component, "Component should not be null");
    if (component.getElement().hasAttribute("slot")) {
      HasItemsAndComponents.super.addComponentAsFirst(component);
    } else {
      listBox.addComponentAsFirst(component);
    }
  }

  @Override
  public void addToPrefix(Component... components) {
    super.addToPrefix(components);
  }

  @Override
  public Stream<Component> getChildren() {
    // do not provide access to items or list box as touching those will
    // hurt
    return Stream.concat(
        super.getChildren().filter(component -> component != listBox),
        listBox.getChildren().filter(component -> !(component instanceof VaadinItem)));
  }

  /**
   * Removes the given child components from this component.
   *
   * <p><em>NOTE:</em> any component with the {@code slot} attribute will be attempted to removed
   * from the light dom of the vcf-multi-select, instead of inside the options drop down.
   *
   * @param components the components to remove
   * @throws IllegalArgumentException if any of the components is not a child of this component
   */
  @Override
  public void remove(Component... components) {
    Objects.requireNonNull(components, "Components should not be null");
    for (Component component : components) {
      if (component.getElement().hasAttribute("slot")) {
        super.remove(component);
      } else {
        listBox.remove(components);
      }
    }
  }

  /**
   * Removes all child components that are not items. To remove all items, reset the data provider
   * or use {@link #setItems(Object[])}.
   *
   * <p><em>NOTE:</em> this will remove all non-items from the drop down and any slotted components
   * from vcf-multi-select's light dom.
   *
   * @see HasComponents#removeAll()
   */
  @Override
  public void removeAll() {
    // Only remove list box children that are not vaadin-item since it makes
    // no sense
    // to allow removing those, causing the component to be in flux state.
    // Also do not remove the list box but remove any slotted components
    // (see add())
    getChildren().forEach(this::remove);
  }

  @Override
  protected boolean hasValidValue() {
    // this is not about whether the value is actually "valid",
    // this is about whether or not is something that should be committed to
    // the _value_ of this field. E.g, it might be a value that is
    // acceptable,
    // but the component status should still be _invalid_.
    /*
     * String selectedKey = getElement().getProperty("value"); T item =
     * keyMapper.get(selectedKey); if (item == null) { return
     * isEmptySelectionAllowed() && isItemEnabled(item); }
     *
     * return isItemEnabled(item);
     */
    return true;
  }

  @Override
  protected void onAttach(AttachEvent attachEvent) {
    super.onAttach(attachEvent);
    initConnector();
  }

  private void initConnector() {
    runBeforeClientResponse(
        ui -> {
          ui.getPage()
              .executeJs("window.Vaadin.Flow.multipleSelectConnector.initLazy($0)", getElement());
          // connector init will handle first data setting
          resetPending = false;
        });
  }

  private boolean isItemEnabled(T item) {
    return (itemEnabledProvider == null) || itemEnabledProvider.test(item);
  }

  private Component createItem(T bean) {
    VaadinItem<T> item = new VaadinItem<>(keyMapper.key(bean), bean);
    updateItem(item);
    return item;
  }

  private void updateItem(VaadinItem<T> vaadinItem) {
    vaadinItem.removeAll();
    T item = vaadinItem.getItem();

    if (vaadinItem == emptySelectionItem) {
      vaadinItem.setText(emptySelectionCaption);
    } else if (getItemRenderer() != null) {
      vaadinItem.add(getItemRenderer().createComponent(item));
    } else if (getItemLabelGenerator() != null) {
      vaadinItem.setText(getItemLabelGenerator().apply(item));
    } else {
      vaadinItem.setText(item.toString());
    }

    if (getItemLabelGenerator() != null) {
      vaadinItem.getElement().setAttribute(LABEL_ATTRIBUTE, getItemLabelGenerator().apply(item));
    } else if (item == emptySelectionItem) {
      vaadinItem.getElement().setAttribute(LABEL_ATTRIBUTE, "");
    } else {
      vaadinItem.getElement().removeAttribute(LABEL_ATTRIBUTE);
    }
    updateItemEnabled(vaadinItem);

    callClientSideRenderIfNotPending();
  }

  private void updateItemEnabled(VaadinItem<T> item) {
    boolean itemEnabled = isItemEnabled(item.getItem());
    boolean disabled = isDisabledBoolean() || !itemEnabled;

    // The disabled attribute should be set when the item is disabled,
    // but not if only the select is disabled, because setting disabled
    // attribute clears the selected value of an item.
    item.getElement().setEnabled(!disabled);
    item.getElement().setAttribute("disabled", !itemEnabled);
  }

  private void refreshItems() {
    getItems().forEach(this::updateItem);
  }

  @SuppressWarnings("unchecked")
  private Stream<VaadinItem<T>> getItems() {
    return listBox
        .getChildren()
        .filter(component -> component instanceof VaadinItem)
        .map(child -> (VaadinItem<T>) child);
  }

  private void reset() {
    keyMapper.removeAll();
    listBox.removeAll();
    clear();
    callClientSideRenderIfNotPending();

    if (isEmptySelectionAllowed()) {
      addEmptySelectionItem();
    }
    getDataProvider().fetch(new Query<>()).map(this::createItem).forEach(this::add);
  }

  private void callClientSideRenderIfNotPending() {

    // reset added at this point to avoid unnecessary selected item update
    if (!resetPending) {
      resetPending = true;
      runBeforeClientResponse(
          ui -> {
            ui.getPage().executeJs("$0.render();", getElement());
            resetPending = false;
          });
    }
  }

  private void onDataChange(DataChangeEvent<T> event) {
    if (event instanceof DataChangeEvent.DataRefreshEvent) {
      T updatedItem = ((DataChangeEvent.DataRefreshEvent<T>) event).getItem();
      Object updatedItemId = getDataProvider().getId(updatedItem);
      getItems()
          .filter(vaadinItem -> updatedItemId.equals(getDataProvider().getId(vaadinItem.getItem())))
          .findAny()
          .ifPresent(this::updateItem);
    } else {
      reset();
    }
  }

  private T getValue(Serializable key) {
    if ((key == null) || "".equals(key)) {
      return null;
    }
    return keyMapper.get(key.toString());
  }

  private void addEmptySelectionItem() {
    if (emptySelectionItem == null) {
      emptySelectionItem = new VaadinItem<>("", null);
    }

    updateItem(emptySelectionItem);
    addComponentAsFirst(emptySelectionItem);
    if (getValue() == null) {
      setValue(null);
    }
  }

  private void removeEmptySelectionItem() {
    if (emptySelectionItem != null) {
      listBox.remove(emptySelectionItem);
    }
    emptySelectionItem = null;
  }

  private void validateSelectionEnabledState(PropertyChangeEvent event) {
    if (!event.isUserOriginated()) {
      return;
    }
    if (!hasValidValue() || isReadOnly()) {
      T oldValue = getValue(event.getOldValue());
      // return the value back on the client side
      try {
        validationRegistration.remove();
        getElement().setProperty("value", keyMapper.key(oldValue));
      } finally {
        registerValidation();
      }
      // Now make sure that the item is still in the correct state
      Optional<VaadinItem<T>> selectedItem =
          getItems().filter(item -> item.getItem() == getValue(event.getValue())).findFirst();

      selectedItem.ifPresent(this::updateItemEnabled);
    }
  }

  private void registerValidation() {
    if (validationRegistration != null) {
      validationRegistration.remove();
    }
    validationRegistration = getElement().addPropertyChangeListener("value", validationListener);
  }

  private void runBeforeClientResponse(SerializableConsumer<UI> command) {
    getElement()
        .getNode()
        .runWhenAttached(ui -> ui.beforeClientResponse(this, context -> command.accept(ui)));
  }

  @Override
  public void updateSelection(Set<T> addedItems, Set<T> removedItems) {
    Set<T> value = new HashSet<>(getValue());
    value.addAll(addedItems);
    value.removeAll(removedItems);
    setValue(value);
  }

  /**
   * Returns an immutable set of the currently selected items. It is safe to invoke other {@code
   * SelectionModel} methods while iterating over the set.
   *
   * <p>There are no guarantees of the iteration order of the returned set of items.
   *
   * @return the items in the current selection, not {@code null}
   */
  @Override
  public Set<T> getSelectedItems() {
    return getValue();
  }

  @Override
  public Registration addSelectionListener(MultiSelectionListener<MultipleSelect<T>, T> listener) {
    return addValueChangeListener(
        event ->
            listener.selectionChange(
                new MultiSelectionEvent<>(this, this, event.getOldValue(), event.isFromClient())));
  }
}