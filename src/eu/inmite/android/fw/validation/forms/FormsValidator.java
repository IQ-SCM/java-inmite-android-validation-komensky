/*
 * Copyright (c) 2013, Inmite s.r.o. (www.inmite.eu).
 *
 * All rights reserved. This source code can be used only for purposes specified
 * by the given license contract signed by the rightful deputy of Inmite s.r.o.
 * This source code can be used only by the owner of the license.
 *
 * Any disputes arising in respect of this agreement (license) shall be brought
 * before the Municipal Court of Prague.
 */

package eu.inmite.android.fw.validation.forms;

import android.app.Activity;
import android.content.Context;
import android.support.v4.app.Fragment;
import android.view.View;
import android.view.ViewTreeObserver;
import eu.inmite.android.fw.validation.exception.FormsValidationException;
import eu.inmite.android.fw.validation.forms.annotations.Condition;
import eu.inmite.android.fw.validation.forms.iface.ICondition;
import eu.inmite.android.fw.validation.forms.iface.IFieldAdapter;
import eu.inmite.android.fw.validation.forms.iface.IValidator;
import eu.inmite.android.fw.validation.forms.validators.ValidatorFactory;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.*;

/**
 * @author Tomas Vondracek
 */
public class FormsValidator {

	public static interface IValidationCallback {
		void validationComplete(boolean result, List<ValidationFail> failedValidations);
	}

	private static final WeakHashMap<Object, Map<View, FieldInfo>> sCachedFieldsByTarget = new WeakHashMap<Object, Map<View, FieldInfo>>();
	private static Map<Object, ViewGlobalFocusChangeListener> sContinuesValidations;

	/**
	 * register custom validator
	 */
	@SuppressWarnings("unchecked")
	public static void registerValidator(Class<? extends IValidator<?>> validator) {
		if (validator == null) {
			throw new IllegalArgumentException("validator cannot be null");
		}

		ValidatorFactory.registerValidatorClasses(validator);
	}

	public static boolean clearCaches() {
		final boolean cleaned = ! sCachedFieldsByTarget.isEmpty();
		sCachedFieldsByTarget.clear();

		return cleaned;
	}

	public static void startContinuousValidation(final Object target, final View formContainer, final IValidationCallback callback) {
		if (formContainer == null) {
			throw new IllegalArgumentException("form container view cannot be null");
		}
		if (target == null) {
			throw new IllegalArgumentException("target cannot be null");
		}

		if (sContinuesValidations == null) {
			sContinuesValidations = new HashMap<Object, ViewGlobalFocusChangeListener>();
		} else if (sContinuesValidations.containsKey(target)) {
			// validation is already running
			return;
		}

		final Map<View, FieldInfo> infoMap = getFieldsForTarget(target);
		final ViewGlobalFocusChangeListener listener = new ViewGlobalFocusChangeListener(infoMap, formContainer, target, callback);
		formContainer.getViewTreeObserver().addOnGlobalFocusChangeListener(listener);
		sContinuesValidations.put(target, listener);

	}

	public static boolean stopContinuousValidation(final Object target) {
		if (sContinuesValidations == null || ! sContinuesValidations.containsKey(target)) {
			return false;
		}
		final ViewGlobalFocusChangeListener removed = sContinuesValidations.remove(target);
		final ViewTreeObserver treeObserver = removed.formContainer.getViewTreeObserver();
		if (treeObserver.isAlive()) {
			treeObserver.removeOnGlobalFocusChangeListener(removed);
			return true;
		}

		return false;
	}

	/**
	 * perform validation over the activity
	 * @param activity activity with views to validate
	 * @param callback callback the will receive result of validation
	 * @return whether the validation succeeded
	 */
	public static boolean validate(Activity activity, IValidationCallback callback) {
		return validate(activity, activity, callback);
	}

	/**
	 * perform validation over the given fragment
	 * @param fragment fragment with views to validate
	 * @param callback callback the will receive result of validation
	 * @return whether the validation succeeded
	 */
	public static boolean validate(Fragment fragment, IValidationCallback callback) {
		return validate(fragment.getActivity(), fragment, callback);
	}

	/**
	 * perform validation over the target object
	 * @return whether the validation succeeded
	 * @throws FormsValidationException
	 */
	public synchronized static boolean validate(Context context, Object target, IValidationCallback callback) throws FormsValidationException {
		if (context == null) {
			throw new IllegalArgumentException("context cannot ben null");
		}
		if (target == null) {
			throw new IllegalArgumentException("target cannot be null");
		}

		final List<ValidationFail> failedValidations = new ArrayList<ValidationFail>();
		boolean result = true;

		final Map<View, FieldInfo> infoMap = getFieldsForTarget(target);
		for (Map.Entry<View, FieldInfo> entry : infoMap.entrySet()) {
			final FieldInfo fieldInfo = entry.getValue();
			final View view = entry.getKey();

			ValidationFail fieldResult = performFieldValidations(context, target, fieldInfo, view);
			if (fieldResult != null) {
				failedValidations.add(fieldResult);
				result = false;
			} // else validation passed

		}

		if (callback != null) {
			Collections.sort(failedValidations, new Comparator<ValidationFail>() {
				@Override
				public int compare(ValidationFail lhs, ValidationFail rhs) {
					return lhs.order < rhs.order ? -1 : (lhs.order == rhs.order ? 0 : 1);
				}
			});
			callback.validationComplete(result, failedValidations);
		}
		return result;
	}

	@SuppressWarnings("unchecked")
	private static ValidationFail performFieldValidations(Context context, Object target, FieldInfo fieldInfo, View view) {
		// first, we need to check if condition is not applied for all validations on field
		if (fieldInfo.condition != null && fieldInfo.condition.validationAnnotation().equals(Condition.class)) {
			// condition is applied to all validations on field
			boolean evaluation = evaluateCondition(target, fieldInfo.condition);
			if (! evaluation) {
				// go to next field
				return null;
			}
		}

		// field validations
		for (ValidationInfo valInfo : fieldInfo.validationInfoList) {
			final Annotation annotation = valInfo.annotation;
			if (fieldInfo.condition != null && fieldInfo.condition.validationAnnotation().equals(annotation.annotationType())) {
				boolean evaluation = evaluateCondition(target, fieldInfo.condition);

				if (! evaluation) {
					// continue to next annotation
					continue;
				}
			}
			final IFieldAdapter adapter = FieldAdapterFactory.getAdapterForField(view, annotation);

			final Object value = adapter.getFieldValue(annotation, target, view);
			final boolean isValid = valInfo.validator.validate(annotation, value);

			if (! isValid) {
				final String message = valInfo.validator.getMessage(context, annotation, value);
				final int order = valInfo.validator.getOrder(annotation);

				// no more validations on this field
				return new ValidationFail(view, message, order);
			}
		}
		return null;
	}

	private static Map<View, FieldInfo> getFieldsForTarget(Object target) {
		Map<View, FieldInfo> infoMap = sCachedFieldsByTarget.get(target);
		if (infoMap == null) {
			infoMap = findFieldsToValidate(target);
			sCachedFieldsByTarget.put(target, infoMap);
		}
		return infoMap;
	}

	/**
	 * find fields on target to validate and prepare for their validation
	 */
	private static Map<View, FieldInfo> findFieldsToValidate(Object target) {
		final Field[] fields = target.getClass().getDeclaredFields();
		if (fields == null || fields.length == 0) {
			return Collections.emptyMap();
		}

		final WeakHashMap<View, FieldInfo> infoMap = new WeakHashMap<View, FieldInfo>(fields.length);
		for (Field field : fields) {
			final List<ValidationInfo> infos = new ArrayList<ValidationInfo>();
			final Annotation[] annotations = field.getDeclaredAnnotations();
			if (annotations.length > 0) {
				if (! View.class.isAssignableFrom(field.getType())) {
					// next field
					continue;
				}
				final View view;
				try {
					field.setAccessible(true);
					view = (View) field.get(target);
				} catch (IllegalAccessException e) {
					throw new FormsValidationException(e);
				}

				for (Annotation annotation : annotations) {
					IValidator validator;
					try {
						validator = ValidatorFactory.getValidator(annotation);
					} catch (IllegalAccessException e) {
						throw new FormsValidationException(e);
					} catch (InstantiationException e) {
						throw new FormsValidationException(e);
					}
					if (validator != null) {
						ValidationInfo info = new ValidationInfo(annotation, validator);
						infos.add(info);
					}
				}

				final Condition conditionAnnotation = field.getAnnotation(Condition.class);
				if (infos.size() > 0) {
					Collections.sort(infos, new Comparator<ValidationInfo>() {
						@Override
						public int compare(ValidationInfo lhs, ValidationInfo rhs) {
							return lhs.order < rhs.order ? -1 : (lhs.order == rhs.order ? 0 : 1);
						}
					});
				}
				FieldInfo fieldInfo = new FieldInfo(conditionAnnotation, infos);
				infoMap.put(view, fieldInfo);
			}
		}
		return infoMap;
	}

	@SuppressWarnings("unchecked")
	private static boolean evaluateCondition(Object target, Condition conditionAnnotation) {
		final View conditionView;
		final int viewId = conditionAnnotation.viewId();

		if (target instanceof Activity) {
			conditionView = ((Activity) target).findViewById(viewId);
		} else if (target instanceof Fragment) {
			conditionView = ((Fragment) target).getView().findViewById(viewId);
		} else if (target instanceof View) {
			conditionView = ((View) target).findViewById(viewId);
		} else {
			throw new FormsValidationException("unknown target " + target);
		}

		Class<? extends ICondition> clazz = conditionAnnotation.value();
		IFieldAdapter adapter = FieldAdapterFactory.getAdapterForField(conditionView, null);
		Object value = adapter.getFieldValue(null, target, conditionView);
		try {
			return clazz.newInstance().evaluate(value);
		} catch (InstantiationException e) {
			throw new FormsValidationException(e);
		} catch (IllegalAccessException e) {
			throw new FormsValidationException(e);
		}
	}

	private static class ValidationInfo {
		private final Annotation annotation;
		private final IValidator validator;
		private final int order;

		private ValidationInfo(Annotation annotation, IValidator validator) {
			this.annotation = annotation;
			this.validator = validator;
			this.order = validator.getOrder(annotation);
		}
	}

	private static class FieldInfo {
		private final Condition condition;
		private final List<ValidationInfo> validationInfoList;

		private FieldInfo(Condition condition, List<ValidationInfo> validationInfoList) {
			this.condition = condition;
			this.validationInfoList = validationInfoList;
		}
	}

	public static final class ValidationFail {
		public final View view;
		public final String message;
		private final int order;

		private ValidationFail(View view, String message, int order) {
			this.view = view;
			this.message = message;
			this.order = order;
		}
	}

	private static class ViewGlobalFocusChangeListener implements ViewTreeObserver.OnGlobalFocusChangeListener {

		private final Map<View, FieldInfo> infoMap;
		private final View formContainer;
		private final Object target;
		private final IValidationCallback callback;

		private View currentlyFocusedView;

		public ViewGlobalFocusChangeListener(Map<View, FieldInfo> infoMap, View formContainer, Object target, IValidationCallback callback) {
			this.infoMap = infoMap;
			this.formContainer = formContainer;
			this.target = target;
			this.callback = callback;
		}

		@Override
		public void onGlobalFocusChanged(View oldFocus, View newFocus) {
			// dunno why, but oldFocus is absolutely wrong
			if (this.currentlyFocusedView != null && this.currentlyFocusedView != newFocus) {
				FieldInfo info = this.infoMap.get(this.currentlyFocusedView);
				if (info != null) {
					final ValidationFail validationFail = performFieldValidations(this.formContainer.getContext(), target, info, this.currentlyFocusedView);

					if (validationFail != null && callback != null) {
						// we have a failed validation
						this.callback.validationComplete(false, Collections.singletonList(validationFail));
					}
				}
			}

			this.currentlyFocusedView = newFocus;
		}
	}
}